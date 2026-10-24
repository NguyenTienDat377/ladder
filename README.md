# ladder

An adaptive-bitrate video platform, built to understand how one actually works.

A *bitrate ladder* is the set of encoded renditions a video is transcoded into — 1080p, 720p, 480p, 240p — so a player can step up or down the rungs as network conditions change. That mechanism is the core of this project, and the reason for its name.

**Status: building.** The whole path works end to end in the browser: sign up, upload, transcode into four renditions, watch with a quality ladder, subscribe, like, and browse a feed and channel pages. Search isn't built, and the gaps below are real. Claims below are marked *designed* or *built* — nothing is marked built until there's code to point at.

---

## What it does

A user uploads a video. It's transcoded into four renditions, split into ~10-second segments, and stored in object storage. A player fetches a manifest, then pulls segments at whichever rendition its current bandwidth supports — dropping to 240p mid-playback when the network degrades, climbing back up when it recovers.

Around that sits the part that's less visible but harder to get right: who is allowed to do what.

---

## Design decisions

Each of these was a fork in the road. The reasoning matters more than the choice.

### Bytes live in object storage; the database stores pointers

`video_resolutions.object_storage_path` holds an S3 key. The video bytes never touch Postgres.

Three reasons. Multi-GB blobs inside the database bloat every backup and replication stream. Object storage supports HTTP range requests natively, which is exactly what segment-by-segment playback needs and what a BLOB column is bad at. And it keeps the database fast at the thing it's good at — querying metadata — instead of being a slow file server.

*Built* — key layout in [`StorageKeys`](src/main/java/com/ladder/storage/StorageKeys.java); the worker uploads segments and records the playlist key.

### One row per rendition, not per segment

`video_resolutions` stores four rows per video, not one row per 10-second chunk. Segment-level rows would multiply table size by two orders of magnitude for data the HLS/DASH manifest already enumerates.

The database answers "which renditions exist and where do they start"; the manifest answers "which segments make up this rendition". Putting segment enumeration in both places means keeping them in sync forever.

*Built* — [`VideoResolutionRepository`](src/main/java/com/ladder/video/VideoResolutionRepository.java).

### Uploads bypass the API

The client never sends video bytes to the Spring server. `POST /api/v1/videos` creates the row and returns a **presigned URL**: a link signed with the storage secret that permits exactly one `PUT`, to one object key, for 15 minutes. The client uploads straight to object storage, then calls `POST /api/v1/videos/{id}/complete`.

Routing a 2GB upload through the API would hold a server thread and its bandwidth for minutes per upload — and Spring rejects multipart bodies over 1MB by default anyway. The object key is derived from the server's own video id, never from the client's filename, so a client can only ever write into the slot it was given.

Because the server isn't involved in the upload, it can't know when one finishes — hence `/complete`, which doesn't take the client's word for it: it checks the object actually exists in storage before queueing work.

*Built* — [`VideoService`](src/main/java/com/ladder/video/VideoService.java).

### One queue message per rendition

Completing an upload publishes four RabbitMQ messages, one per `(video_id, resolution)`. One message = one worker job = one `video_resolutions` row. FFmpeg can encode the whole ladder in one command, slightly more efficiently; it was rejected because per-rendition jobs retry independently (a failed 1080p doesn't redo 240p), run in parallel across workers, and make partial progress visible.

Messages carry only ids — never paths, bitrates or bytes. The worker derives everything else, so a message that waits in the queue through a config change still gets the current settings, and a forged message can't point the worker at an arbitrary file.

*Built* — [`TranscodeWorker`](src/main/java/com/ladder/transcode/TranscodeWorker.java), [`TranscodeQueueConfig`](src/main/java/com/ladder/transcode/TranscodeQueueConfig.java).

### Every job is safe to run twice

RabbitMQ delivers *at least* once: a worker that crashes after finishing but before acknowledging gets its job redelivered. So the rendition write is an `INSERT … ON CONFLICT DO UPDATE` against `UNIQUE (video_id, resolution)`, not a plain insert — a duplicate overwrites the same row instead of failing a job that actually succeeded. Output paths are deterministic, so the re-uploaded segments overwrite cleanly too.

Failures are bounded: a job is retried twice, then dead-lettered, and the video is marked `FAILED`. Without a ceiling, a corrupt upload — which fails every time — would retry forever and starve real work. Workers take one job at a time (`prefetch=1`), because FFmpeg saturates every core and two concurrent encodes just make both slower.

Status changes are single conditional statements (`UPDATE … WHERE status = 'UPLOADING'`), so two concurrent `/complete` calls can't both queue the transcode — the database lets exactly one win.

*Built and tested* — duplicate delivery leaves one row; a job for a missing source is retried, dead-lettered, and flips the video to `FAILED`.

### The last rendition makes the video playable

The master playlist — the one URL a player starts from, listing all four renditions and the bandwidth each needs — can only be written once every rendition exists. There's no separate coordinator process: after each rendition, the worker that finished it checks whether all four rows exist, and if so writes `master.m3u8` and flips the video to `READY`.

Two workers can finish the last renditions at the same moment. No lock is needed: each checks only after its own row has committed, so the last to commit always sees the full set. Both may write the master — harmless, the content is identical — and `UPDATE … WHERE status = 'PROCESSING'` lets exactly one flip the status. The master is written *before* the status changes, so `READY` is never visible before the file a player needs.

*Built* — [`TranscodeCoordinator`](src/main/java/com/ladder/transcode/TranscodeCoordinator.java). Verified: FFmpeg opens the master as a four-variant adaptive stream and every rendition decodes cleanly. The concurrent-finish case is reasoned, not yet tested (workers currently run one at a time).

### Duration and the thumbnail come from the 240p job

Exactly one job per video, the lowest rung's, runs `ffprobe` on the source for the duration and cuts one JPEG frame a quarter of the way in (the first frame is often a black intro, and a fixed offset would fall outside a short clip). It happens *before* that job's row is upserted, and a video turns READY only when all four rows exist, so a READY video already has both. They are extras: if probing or ffmpeg fails, the job logs a warning and carries on, because failing would retry and eventually dead-letter a transcode that is fine. Redelivery is safe (a fixed storage key, and the database writes SET values).

The picture is served by the API, `GET /api/v1/videos/{id}/thumbnail`, not through a presigned storage URL: a presigned URL changes on every request, so no browser or CDN could ever cache it. The API sends an ETag (a repeat visit is a 304) and `Cache-Control: public, max-age=3600` for PUBLIC and UNLISTED videos, `private` for PRIVATE ones, under the same watch rule as playback.

*Built* ([`FfmpegTranscoder`](src/main/java/com/ladder/transcode/FfmpegTranscoder.java), [`TranscodeWorker`](src/main/java/com/ladder/transcode/TranscodeWorker.java)). Videos transcoded before this existed have neither; to backfill one, republish its `TranscodeJob(videoId, "240p")` (the re-run is a no-op for the rest, the upsert and the READY flip are idempotent). No script for that yet.

### Keyframes are pinned so renditions align

A player can only switch rendition at a keyframe, so every rendition must have keyframes at identical timestamps. FFmpeg's default places them at scene changes, which differ per resolution. The encoder pins a keyframe every 48 frames with scene-cut detection off (`-g 48 -keyint_min 48 -sc_threshold 0`); 48 frames at 24fps is 2s, which divides the 10s segment evenly.

*Built* — [`FfmpegTranscoder`](src/main/java/com/ladder/transcode/FfmpegTranscoder.java). Verified on a 24fps clip: 240p and 480p keyframes identical, 3 × 10.0s segments. Not yet correct for other frame rates (see Known gaps).

### Access control is scoped, not flat

This is the piece most portfolio projects skip, and it's where the design gets interesting.

Roles are **not global**. A user can be a moderator on one channel and an ordinary viewer on another, so a role assignment is meaningless without a channel attached. But platform moderation is the exact inverse — a platform moderator needs authority over videos on channels they've never joined.

Two scopes, so two assignment tables: `channel_memberships` (carries `channel_id`) and `platform_role_assignments` (doesn't).

The tempting alternative is one table with a nullable `channel_id`, where `NULL` means "global". It was rejected for a specific reason: unique constraints treat NULLs as distinct, so that table can't cleanly enforce one grant per user per role — and more importantly, forgetting an `IS NULL` in a single `WHERE` clause silently promotes a channel-scoped grant to a global one. That's an authorization bug that **fails open**, which is the worst way for one to fail. Splitting the tables makes the mistake structurally impossible rather than merely discouraged.

*Schema built; enforcement designed.* The tables and seed roles exist, but no endpoint checks permissions yet — every route is currently open (see Known gaps).

### One role per channel

`channel_memberships` has primary key `(channel_id, user_id)` — `role_id` is deliberately excluded, so a user holds exactly one role per channel. Combined abilities are expressed by defining a role with both permission sets, not by stacking rows.

The cost is compositional flexibility. The benefit is that every permission check resolves exactly one role, and "why can this person do that" has one unambiguous answer. GitHub makes the same call — one permission level per repository. Widening to `(channel_id, user_id, role_id)` later is a small migration if it's ever needed.

*Schema built; enforcement designed.*

### Roles are rows, not an enum

`roles` and `permissions` are lookup tables. Creating a role is an `INSERT`, not a schema migration, and permissions attach dynamically through `role_permissions`.

If roles were a Postgres `ENUM`, the permission set would necessarily live in application code and `role_permissions` would be decorative. The whole reason to make RBAC table-driven is that authorization becomes configuration.

Status fields (`video.status`, `video.visibility`, `resolution`) use `VARCHAR` + `CHECK` rather than native enum types — same validity guarantee, but changing the allowed set is dropping and re-adding a constraint instead of an `ALTER TYPE` migration. Adding 1440p should not require a type change.

*Built* (schema) — the role and permission tables are seeded in the migration; nothing reads them yet.

### UUIDv7 primary keys

The leading 48 bits are a Unix millisecond timestamp, so values are time-ordered. Inserts land at the rightmost B-tree leaf rather than scattering across the index — fewer page splits, less fragmentation, and the hot write region stays resident in the buffer pool. Postgres 18 provides `uuidv7()` natively; earlier versions generate them application-side, which gets the identical benefit since it comes from the value's bit layout, not from where it was produced.

The trade-off, stated plainly: **a UUIDv7 leaks its creation time.** Video and comment IDs appear in public URLs, so anyone holding two of them learns their relative creation order and roughly when each was made. Acceptable here — `created_at` is public anyway — but it's a real consideration and would need an opaque public slug if creation timing were ever sensitive.

*Built* — JPA entities generate UUIDv7 in Java (Hibernate `@UuidGenerator(style = VERSION_7)`), because Hibernate needs the id before the insert; rows written by native SQL use the column's `DEFAULT uuidv7()`.

### View counts are eventually consistent, on purpose

`videos.view_count` is a cache, not a source of truth.

`UPDATE videos SET view_count = view_count + 1` on every watch event does two bad things: it serializes every concurrent viewer of a popular video behind a single row lock, and it generates a row version per increment, handing a growing pile of work to autovacuum. Watch events are firehose volume — dozens per viewing session — so the naive write is the first thing to fall over under load.

The intended path is events → queue → Redis counter → periodic flush into the column. Which means the number displayed is approximate by design. It's a display value; nothing that requires exactness should be computed from it.

*Built* ([`ViewCountService`](src/main/java/com/ladder/video/ViewCountService.java)). `POST /api/v1/videos/{id}/view` counts a viewer once per 30 minutes (an atomic `SET NX EX`) and adds to a Redis hash. Every minute a job `RENAME`s that hash aside, adds its counts to `view_count` in one transaction, then deletes it; a batch left behind by a crash is finished first. The number shown is the stored count plus what is still pending. Limit: assumes one API instance.

### Subscriber and like counts are exact, on purpose

Subscribing and liking are deliberate clicks, not a firehose, so they take the other route: the `subscriptions` and `video_reactions` tables are the source of truth, and `channels.subscriber_count` / `videos.like_count` / `dislike_count` are recomputed from them **in the same transaction** as each write. A recount is exact, heals any drift and makes repeating a request harmless. Each write first locks the channel or video row: under Postgres's default isolation two racing recounts can each miss the other's insert and store a wrong number, and the lock makes the second writer wait. The cost is that writes to one hot channel or video run one at a time; the way out is the Redis path above.

*Built* ([`EngagementService`](src/main/java/com/ladder/video/EngagementService.java)), including a test that fires 20 users at one channel and one video at once and checks every counter against its table.

---

### The frontend is plain static pages

No framework and no build step: `index.html`, `watch.html`, `channel.html`, `login.html` and `upload.html` under [`static/`](src/main/resources/static/), served by Spring itself, sharing one stylesheet and one small script ([`app.js`](src/main/resources/static/app.js)). Being the same origin as the API is what makes the session cookie and CSRF protection simple: the cookie travels on its own, and the page copies the `XSRF-TOKEN` cookie into an `X-XSRF-TOKEN` header on every write. Anything a user typed (titles, names, descriptions) is put on the page as text, never as HTML.

The upload page sends the file straight to storage with the presigned URL, with progress, then calls `/complete` and polls until the video is `READY`. The watch page's quality chip opens the ladder: every rung with its bitrate, a dashed line at the connection speed hls.js measured, and the option to hold one rung or go back to Auto.

*Built.* Checked by driving a real browser through sign-up, upload, playback, subscribe and like; there are no automated browser tests yet, and nothing checks the layout on a real phone, only a 390px-wide window.

## Schema

Ten tables, in two groups.

**Content:** `users`, `channels`, `videos`, `video_resolutions`, `comments`

**Access control:** `roles`, `permissions`, `role_permissions`, `channel_memberships`, `platform_role_assignments`

Comments are threaded via an adjacency list (`parent_comment_id` self-reference) walked with a recursive CTE, with a depth cap. Materialized path was the alternative if tree reads became hot; nested set was rejected outright — the update cost is brutal for a comment tree that changes constantly.

Full DDL with inline rationale: [`V1__create_video_platform_schema.sql`](src/main/resources/db/migration/V1__create_video_platform_schema.sql), applied by Flyway on startup. *Built.*

---

## Known gaps

Listed so they read as decisions rather than oversights.

- **No `watch_events`** — deliberately absent. That data belongs in a columnar store (ClickHouse) fed by a queue, not in the transactional database.
- **Permission checks are uncached** — the check is three joins, running on nearly every authorized endpoint. The fix is resolving a user's permission set once and caching it in Redis or embedding it in JWT claims. The catch: both make revocation asynchronous, so a moderator stripped of their role keeps it until the token or cache entry expires. For someone removed for abuse, that window matters. Mitigations are short TTLs, a token-version column, or a denylist consulted on sensitive operations only. This is an application-layer problem the schema doesn't solve.
- **No partitioning** on any high-volume table.
- **No resumable upload** — a 2GB upload dying at 90% currently restarts from zero. S3 multipart or the tus protocol is the fix.
- **Master playlist omits `RESOLUTION` and `CODECS`** — both optional. Encodes keep the source's aspect ratio, so the nominal 16:9 width would be wrong for vertical or 4:3 video; they need the worker to probe the real output first.
- **Queueing isn't atomic with the status change** — Postgres and RabbitMQ can't share a transaction. If publishing fails, the status is reverted so the client can retry; a crash *between* the two steps still strands the video in `PROCESSING`. The full fix is a transactional outbox table.
- **Keyframe interval assumes 24fps** — on 30fps sources 48 frames is 1.6s, which doesn't divide 10s. Renditions still align with each other, but segments come out uneven. Fix: probe the frame rate and use `2 × fps`.
- **No upscale guard** — a 480p upload still produces a "1080p" rendition, which is a blown-up 480p costing more bandwidth for nothing.
- **No size or quota check** — a presigned URL accepts an upload of any size, and videos whose upload never completes stay in `UPLOADING` forever.
- **The worker runs inside the API process** — it's designed as its own image (so the API container never ships FFmpeg); the [`Dockerfile`](Dockerfile) is a draft and doesn't work yet.
- **No automated tests** — everything above was verified by hand against the running stack; Testcontainers is planned.

---

## Architecture sketch

```
client ──presigned PUT──> object storage (RustFS, S3 API)        built
   │
   └──> API (Spring Boot) ──> Postgres                         built (RBAC: schema only)
                │
                └──> RabbitMQ ──> transcode worker ──> renditions   built
                │                                 └─> master playlist   built
                └──> queue ──> watch events ──> columnar store       designed
```

Three groups with genuinely different load characteristics, which is what drives the architecture more than the feature list does:

| Group | Flows | Shape | Store |
|---|---|---|---|
| Transactional | auth, upload, comments | low volume, must be exact | Postgres |
| Read-heavy | playback, discovery | ~1000:1 read/write | cache + CDN |
| Event firehose | watch events | huge volume, small loss tolerable | queue → columnar |

---

## Stack

Spring Boot 4 · Postgres 18 + Flyway · RustFS (S3-compatible) · RabbitMQ · FFmpeg · Redis · Docker Compose

Object storage was planned as MinIO; its image is no longer published on Docker Hub, so RustFS stands in. The app talks to it through the plain S3 API (MinIO's Java client), so any S3-compatible store works. Redis is wired up but not used yet.

---

## Running it locally

Needs Docker, a JDK (the toolchain is set in `build.gradle`) and `ffmpeg` on your `PATH` — the worker runs in-process for now.

```bash
./gradlew bootRun     # starts Postgres, RabbitMQ, Redis and RustFS via compose.yaml, applies the schema
```

`bootRun` keeps running for as long as the app does. If you start containers yourself, start all of them (`docker compose up -d`): Spring Boot skips its own startup when any service is already running.

Then open http://localhost:8080, choose **Sign in → Create account**, and upload from the **Upload** button. Processing takes a minute or two for a short clip, and the page tells you when it's ready.

No video handy? `ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=24:duration=30 -f lavfi -i sine=duration=30 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest video.mp4`

The same thing from a terminal is the API: create the video (`POST /api/v1/videos`, which needs a login session and the CSRF header), `PUT` the file to the returned `uploadUrl`, then `POST /api/v1/videos/{id}/complete`.

The storage console is at http://localhost:9001; the credentials are in `compose.yaml` (local development only).

---

## Why this exists

Reimplementing a solved problem at small scale is a good way to find out which parts were actually hard. Most of the interesting decisions here weren't about video at all — they were about which guarantees to give up, and being able to say why.