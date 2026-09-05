# ladder

An adaptive-bitrate video platform, built to understand how one actually works.

A *bitrate ladder* is the set of encoded renditions a video is transcoded into — 1080p, 720p, 480p, 240p — so a player can step up or down the rungs as network conditions change. That mechanism is the core of this project, and the reason for its name.

**Status: design phase.** The schema below is written; the services are not. This README documents the design and the reasoning behind it. Claims are marked as *designed* or *built* — nothing is marked built until it is, and until there's something to point at.
---

## What it does

A user uploads a video. It's transcoded into four renditions, split into ~10-second segments, and stored in object storage. A player fetches a manifest, then pulls segments at whichever rendition its current bandwidth supports — dropping to 240p mid-playback when the network degrades, climbing back up when it recovers.

Around that sits the part that's less visible but harder to get right: who is allowed to do what.

---

## Design decisions

Each of these was a fork in the road. The reasoning matters more than the choice.

### Bytes live in object storage; the database stores pointers

`video_resolutions.object_storage_path` holds a MinIO/S3 key. The video bytes never touch Postgres.

Three reasons. Multi-GB blobs inside the database bloat every backup and replication stream. Object storage supports HTTP range requests natively, which is exactly what segment-by-segment playback needs and what a BLOB column is bad at. And it keeps the database fast at the thing it's good at — querying metadata — instead of being a slow file server.

*Designed.*

### One row per rendition, not per segment

`video_resolutions` stores four rows per video, not one row per 10-second chunk. Segment-level rows would multiply table size by two orders of magnitude for data the HLS/DASH manifest already enumerates.

The database answers "which renditions exist and where do they start"; the manifest answers "which segments make up this rendition". Putting segment enumeration in both places means keeping them in sync forever.

*Designed.*

### Access control is scoped, not flat

This is the piece most portfolio projects skip, and it's where the design gets interesting.

Roles are **not global**. A user can be a moderator on one channel and an ordinary viewer on another, so a role assignment is meaningless without a channel attached. But platform moderation is the exact inverse — a platform moderator needs authority over videos on channels they've never joined.

Two scopes, so two assignment tables: `channel_memberships` (carries `channel_id`) and `platform_role_assignments` (doesn't).

The tempting alternative is one table with a nullable `channel_id`, where `NULL` means "global". It was rejected for a specific reason: unique constraints treat NULLs as distinct, so that table can't cleanly enforce one grant per user per role — and more importantly, forgetting an `IS NULL` in a single `WHERE` clause silently promotes a channel-scoped grant to a global one. That's an authorization bug that **fails open**, which is the worst way for one to fail. Splitting the tables makes the mistake structurally impossible rather than merely discouraged.

*Designed.*

### One role per channel

`channel_memberships` has primary key `(channel_id, user_id)` — `role_id` is deliberately excluded, so a user holds exactly one role per channel. Combined abilities are expressed by defining a role with both permission sets, not by stacking rows.

The cost is compositional flexibility. The benefit is that every permission check resolves exactly one role, and "why can this person do that" has one unambiguous answer. GitHub makes the same call — one permission level per repository. Widening to `(channel_id, user_id, role_id)` later is a small migration if it's ever needed.

*Designed.*

### Roles are rows, not an enum

`roles` and `permissions` are lookup tables. Creating a role is an `INSERT`, not a schema migration, and permissions attach dynamically through `role_permissions`.

If roles were a Postgres `ENUM`, the permission set would necessarily live in application code and `role_permissions` would be decorative. The whole reason to make RBAC table-driven is that authorization becomes configuration.

Status fields (`video.status`, `video.visibility`, `resolution`) use `VARCHAR` + `CHECK` rather than native enum types — same validity guarantee, but changing the allowed set is dropping and re-adding a constraint instead of an `ALTER TYPE` migration. Adding 1440p should not require a type change.

*Designed.*

### UUIDv7 primary keys

The leading 48 bits are a Unix millisecond timestamp, so values are time-ordered. Inserts land at the rightmost B-tree leaf rather than scattering across the index — fewer page splits, less fragmentation, and the hot write region stays resident in the buffer pool. Postgres 18 provides `uuidv7()` natively; earlier versions generate them application-side, which gets the identical benefit since it comes from the value's bit layout, not from where it was produced.

The trade-off, stated plainly: **a UUIDv7 leaks its creation time.** Video and comment IDs appear in public URLs, so anyone holding two of them learns their relative creation order and roughly when each was made. Acceptable here — `created_at` is public anyway — but it's a real consideration and would need an opaque public slug if creation timing were ever sensitive.

*Designed.*

### View counts are eventually consistent, on purpose

`videos.view_count` is a cache, not a source of truth.

`UPDATE videos SET view_count = view_count + 1` on every watch event does two bad things: it serializes every concurrent viewer of a popular video behind a single row lock, and it generates a row version per increment, handing a growing pile of work to autovacuum. Watch events are firehose volume — dozens per viewing session — so the naive write is the first thing to fall over under load.

The intended path is events → queue → Redis counter → periodic flush into the column. Which means the number displayed is approximate by design. It's a display value; nothing that requires exactness should be computed from it.

*Designed. Not yet built — the counter columns currently have no backing tables (see Known gaps).*

---

## Schema

Ten tables, in two groups.

**Content:** `users`, `channels`, `videos`, `video_resolutions`, `comments`

**Access control:** `roles`, `permissions`, `role_permissions`, `channel_memberships`, `platform_role_assignments`

Comments are threaded via an adjacency list (`parent_comment_id` self-reference) walked with a recursive CTE, with a depth cap. Materialized path was the alternative if tree reads became hot; nested set was rejected outright — the update cost is brutal for a comment tree that changes constantly.

Full DDL with inline rationale: [`V1__create_video_platform_schema.sql`](#).

---

## Known gaps

Listed so they read as decisions rather than oversights.

- **No `subscriptions` table** — `channels.subscriber_count` has nothing behind it yet.
- **No `video_reactions` table** — same for `like_count` / `dislike_count`.
- **No `watch_events`** — deliberately absent. That data belongs in a columnar store (ClickHouse) fed by a queue, not in the transactional database.
- **Permission checks are uncached** — the check is three joins, running on nearly every authorized endpoint. The fix is resolving a user's permission set once and caching it in Redis or embedding it in JWT claims. The catch: both make revocation asynchronous, so a moderator stripped of their role keeps it until the token or cache entry expires. For someone removed for abuse, that window matters. Mitigations are short TTLs, a token-version column, or a denylist consulted on sensitive operations only. This is an application-layer problem the schema doesn't solve.
- **No partitioning** on any high-volume table.
- **No resumable upload** — a 2GB upload dying at 90% currently restarts from zero. S3 multipart or the tus protocol is the fix.

---

## Architecture sketch

```
client ──presigned URL──> object storage (MinIO)
   │
   └──> API (Spring Boot) ──> Postgres          metadata, auth, RBAC
                │
                └──> queue ──> transcode worker ──> renditions + manifest
                │
                └──> queue ──> watch events ──> columnar store
```

Three groups with genuinely different load characteristics, which is what drives the architecture more than the feature list does:

| Group | Flows | Shape | Store |
|---|---|---|---|
| Transactional | auth, upload, comments | low volume, must be exact | Postgres |
| Read-heavy | playback, discovery | ~1000:1 read/write | cache + CDN |
| Event firehose | watch events | huge volume, small loss tolerable | queue → columnar |

---

## Stack

Postgres 18 · Spring Boot · MinIO · Redis · RabbitMQ · FFmpeg · Docker

---

## Why this exists

Reimplementing a solved problem at small scale is a good way to find out which parts were actually hard. Most of the interesting decisions here weren't about video at all — they were about which guarantees to give up, and being able to say why.