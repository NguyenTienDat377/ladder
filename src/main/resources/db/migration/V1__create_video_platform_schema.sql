-- ============================================================================
-- V1__create_video_platform_schema.sql
--
-- Video platform schema: core content tables + role-based access control.
-- Target: PostgreSQL 18+ (uses the native uuidv7() function).
--
-- ON POSTGRES < 18:
--   uuidv7() does not exist. Either generate UUIDv7 in the application layer
--   (Java: com.github.f4b6a3:uuid-creator, or java.util.UUID from a v7 lib)
--   and drop the DEFAULT clauses, or install the pg_uuidv7 extension.
--   The index-locality benefit comes from the VALUE's bit layout, not from
--   where it was generated -- app-side generation is equally effective.
--
-- WHY UUIDv7 AT ALL:
--   The leading 48 bits are a Unix millisecond timestamp, so values are
--   time-ordered. New rows land at the rightmost B-tree leaf instead of
--   scattering across the index. That means fewer page splits, less index
--   fragmentation, and the hot write region stays in the buffer pool.
--
--   TRADE-OFF: a UUIDv7 leaks its creation time to anyone holding it. For
--   video_id and comment_id -- which appear in public URLs -- this exposes
--   roughly when a record was created and the relative order of any two IDs.
--   Acceptable here (created_at is public anyway), but it IS a real
--   consideration, not a theoretical one. If you ever need an opaque public
--   identifier, add a separate random slug column and keep the UUIDv7 internal.
-- ============================================================================


-- ============================================================================
-- SECTION 1: CORE CONTENT TABLES
-- ============================================================================

-- ----------------------------------------------------------------------------
-- users
--
-- NAMING NOTE: the table is "users", not "user". USER is a reserved keyword in
-- PostgreSQL (it resolves to the current session user), so `SELECT * FROM user`
-- fails and every reference would need double-quoting forever. Plural avoids it.
--
-- All identifiers here are snake_case. Postgres folds unquoted identifiers to
-- lowercase, so "ChannelId" silently becomes "channelid" -- mixing cases in the
-- source just makes the DDL disagree with what the database actually stores.
-- ----------------------------------------------------------------------------
CREATE TABLE users (
    user_id      UUID         PRIMARY KEY DEFAULT uuidv7(),
    email        VARCHAR(320) NOT NULL UNIQUE,
    user_name    VARCHAR(100) NOT NULL,
    user_handle  VARCHAR(50)  NOT NULL UNIQUE,
    activated    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- email and user_handle are both natural lookup keys (login, /@handle URLs).
-- The UNIQUE constraints above already create indexes, so no extra ones needed.
--
-- Two additions to your draft, both enforcing things your design implied:
--   - UNIQUE on email and user_handle. Without these, nothing stops two rows
--     sharing a handle, and /@dat becomes ambiguous.
--   - Length caps. 320 is the RFC-permitted maximum for an email address.


-- ----------------------------------------------------------------------------
-- channels
--
-- A user may own many channels; a channel owns videos. This indirection is
-- deliberate -- it is why RBAC below is scoped per-channel rather than global.
-- ----------------------------------------------------------------------------
CREATE TABLE channels (
    channel_id       UUID         PRIMARY KEY DEFAULT uuidv7(),
    user_id          UUID         NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    channel_name     VARCHAR(100) NOT NULL,
    channel_handle   VARCHAR(50)  NOT NULL UNIQUE,
    subscriber_count BIGINT       NOT NULL DEFAULT 0,
    video_count      INTEGER      NOT NULL DEFAULT 0,
    activated        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_channels_owner ON channels(user_id);

-- ON DELETE CASCADE is your choice from the draft, kept as specified.
-- Be aware of what it means in practice: deleting one user row silently
-- destroys their channels, and (via the cascade on videos below) every video
-- and comment underneath. There is no undo and no audit trail.
--
-- The common production alternative is a soft delete -- you already have the
-- `activated` flag, so flipping it to FALSE deactivates the account while
-- preserving content and the ability to restore. Worth deciding consciously
-- rather than by default; for a portfolio project, either answer is defensible
-- as long as you can say why you picked it.


-- ----------------------------------------------------------------------------
-- videos
--
-- Metadata only. The actual bytes live in object storage (MinIO/S3); this
-- table stores the metadata the DB is good at querying, and video_resolutions
-- below stores the pointers to where each rendition's bytes actually are.
-- ----------------------------------------------------------------------------
CREATE TABLE videos (
    video_id      UUID         PRIMARY KEY DEFAULT uuidv7(),
    channel_id    UUID         NOT NULL REFERENCES channels(channel_id) ON DELETE CASCADE,
    title         VARCHAR(200) NOT NULL,
    description   TEXT,
    duration_secs INTEGER,
    visibility    VARCHAR(20)  NOT NULL DEFAULT 'PRIVATE'
                  CHECK (visibility IN ('PUBLIC', 'UNLISTED', 'PRIVATE')),
    status        VARCHAR(20)  NOT NULL DEFAULT 'UPLOADING'
                  CHECK (status IN ('UPLOADING', 'PROCESSING', 'READY', 'FAILED', 'TAKEN_DOWN')),
    view_count    BIGINT       NOT NULL DEFAULT 0,
    like_count    BIGINT       NOT NULL DEFAULT 0,
    dislike_count BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_videos_channel_time ON videos(channel_id, created_at DESC);
CREATE INDEX idx_videos_status ON videos(status) WHERE status <> 'READY';

-- WHY `status` EXISTS (not in your draft):
--   Upload is asynchronous. The client gets a presigned URL, uploads directly
--   to object storage, and the backend creates the row before transcoding has
--   produced a single rendition. Without a status column there is no way to
--   express "this row exists but is not yet playable", and the playback API
--   would happily serve a video with zero available resolutions.
--   The partial index above keeps the "what still needs work" queue cheap --
--   it only indexes the small minority of rows that aren't READY.
--
-- WHY `visibility` EXISTS:
--   Private/unlisted videos are the reason signed CDN URLs matter. Without
--   this column every video is implicitly public and that whole branch of the
--   playback design has nowhere to hang.
--
-- ABOUT THE THREE COUNTER COLUMNS -- READ THIS ONE:
--   view_count/like_count/dislike_count are denormalized caches, NOT sources
--   of truth. Watch events are firehose volume (dozens per viewing session);
--   writing UPDATE videos SET view_count = view_count + 1 on every event does
--   two bad things: it serializes all concurrent viewers of a popular video
--   behind a row lock, and it generates a new row version per increment,
--   piling work onto autovacuum.
--
--   The intended path is: events -> queue -> Redis counter -> periodic flush
--   into this column. That means these columns are eventually consistent by
--   design. Treat them as display values; never compute billing, ranking
--   thresholds, or anything requiring exactness directly from them.
--
--   NOTE: like_count/dislike_count have no backing table in this schema yet.
--   Once you add a `video_reactions` table (user_id, video_id, reaction_type),
--   these become materialized aggregates of it. Until then they are counters
--   with no source of truth to reconcile against -- a known gap, not an
--   oversight.


-- ----------------------------------------------------------------------------
-- video_resolutions
--
-- One row per (video, rendition). This is the bridge between the relational
-- world and object storage: object_storage_path is the key/prefix your backend
-- hands to the MinIO/S3 client to produce a playable (optionally signed) URL.
-- ----------------------------------------------------------------------------
CREATE TABLE video_resolutions (
    video_resolution_id UUID         PRIMARY KEY DEFAULT uuidv7(),
    video_id            UUID         NOT NULL REFERENCES videos(video_id) ON DELETE CASCADE,
    resolution          VARCHAR(10)  NOT NULL
                        CHECK (resolution IN ('240p', '480p', '720p', '1080p')),
    object_storage_path VARCHAR(512) NOT NULL,
    segment_count       INTEGER,
    bitrate_kbps        INTEGER,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- A video cannot have two 720p renditions. Without this, a retried or
    -- duplicated transcode job silently inserts a second row and playback
    -- picks one arbitrarily.
    CONSTRAINT uq_video_resolution UNIQUE (video_id, resolution)
);

CREATE INDEX idx_video_resolutions_video ON video_resolutions(video_id);

-- WHY VARCHAR + CHECK INSTEAD OF A NATIVE ENUM TYPE:
--   A native `CREATE TYPE ... AS ENUM` is faster and more compact, but adding
--   a value requires ALTER TYPE ADD VALUE (a migration), and removing or
--   reordering values is genuinely painful. VARCHAR + CHECK gets the same
--   validity enforcement, and changing the allowed set is just dropping and
--   re-adding a constraint. When you add 1440p or 4K later you will be glad.
--   (Same reasoning applies to `status` and `visibility` above.)
--
-- WHAT THIS TABLE DELIBERATELY DOES NOT DO:
--   It stores one row per rendition, not one row per 10-second segment. That
--   is correct: segment-level rows would be enormous, and the HLS/DASH
--   manifest file already enumerates segments. object_storage_path should
--   point at the rendition's prefix or its playlist, and the player resolves
--   individual segments from the manifest -- not from the database.


-- ----------------------------------------------------------------------------
-- comments
--
-- Threading uses the adjacency list pattern: each comment stores its parent's
-- id, and a recursive CTE walks the tree.
-- ----------------------------------------------------------------------------
CREATE TABLE comments (
    comment_id        UUID        PRIMARY KEY DEFAULT uuidv7(),
    video_id          UUID        NOT NULL REFERENCES videos(video_id) ON DELETE CASCADE,
    user_id           UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    parent_comment_id UUID        REFERENCES comments(comment_id) ON DELETE CASCADE,
    body              TEXT        NOT NULL,
    is_hidden         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Top-level comments for a video, newest first -- the common read path.
CREATE INDEX idx_comments_video_time ON comments(video_id, created_at DESC);
-- Replies to a given comment. Partial: only non-NULL parents are worth indexing.
CREATE INDEX idx_comments_parent ON comments(parent_comment_id)
    WHERE parent_comment_id IS NOT NULL;

-- parent_comment_id is NULLABLE by design -- NULL means "top-level comment".
-- This is the one place a nullable FK is the right call, because the null
-- genuinely means absence of a parent, not a special mode.
--
-- `is_hidden` exists so moderation can suppress a comment without deleting it.
-- A hard DELETE on a parent cascades away the entire reply subtree, which
-- destroys other people's content as collateral damage.
--
-- FETCHING A THREAD (recursive CTE over the adjacency list):
--
--   WITH RECURSIVE thread AS (
--       SELECT comment_id, parent_comment_id, body, created_at, 1 AS depth
--       FROM comments
--       WHERE video_id = :video_id AND parent_comment_id IS NULL
--     UNION ALL
--       SELECT c.comment_id, c.parent_comment_id, c.body, c.created_at, t.depth + 1
--       FROM comments c
--       JOIN thread t ON c.parent_comment_id = t.comment_id
--       WHERE t.depth < 10          -- depth cap: guards against runaway recursion
--   )
--   SELECT * FROM thread ORDER BY created_at;


-- ============================================================================
-- SECTION 2: ROLE-BASED ACCESS CONTROL
--
-- The design problem this section solves: roles here are not global. A user can
-- be a moderator on one channel and a plain viewer on another -- so a role
-- assignment must be scoped to a channel. But platform moderation is the
-- opposite: a platform moderator needs authority over videos on channels they
-- have no membership in at all.
--
-- Two scopes, therefore two assignment tables.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- roles
--
-- Roles are ROWS, not an enum type. This is the point of table-driven RBAC:
-- creating a role is an INSERT, not a schema migration, and permissions attach
-- to it dynamically through role_permissions. If roles were an enum, the
-- permission set would be hardcoded in application code and role_permissions
-- would be pointless.
-- ----------------------------------------------------------------------------
CREATE TABLE roles (
    role_id     INTEGER     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    role_name   VARCHAR(50) NOT NULL UNIQUE,
    scope       VARCHAR(20) NOT NULL CHECK (scope IN ('CHANNEL', 'PLATFORM')),
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- WHY INTEGER IDENTITY HERE, NOT UUIDv7:
--   The UUIDv7 argument is about insert locality under high write volume.
--   This table will hold maybe a dozen rows, forever, and its ids are never
--   exposed in URLs. A small integer key keeps the junction tables narrow and
--   their indexes dense. Use the right key for the access pattern, not one
--   rule everywhere -- that contrast is itself worth being able to explain.
--
-- WHY `scope` IS HERE even though the two assignment tables below already
-- separate channel roles from platform roles:
--   It is a defense-in-depth check. Nothing structurally prevents inserting
--   role_id for 'platform_admin' into channel_memberships; the scope column
--   lets application code (or a trigger) reject that. It is redundant with the
--   table split by design -- redundancy is the point.


-- ----------------------------------------------------------------------------
-- permissions
--
-- The atomic verbs. Application code checks permission keys, never role names.
-- Checking `role == 'creator'` scatters authorization logic across the codebase
-- and means adding a role requires editing every check site.
-- ----------------------------------------------------------------------------
CREATE TABLE permissions (
    permission_id  INTEGER      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    permission_key VARCHAR(100) NOT NULL UNIQUE,
    description    TEXT
);


-- ----------------------------------------------------------------------------
-- role_permissions
--
-- Many-to-many. This is the indirection that makes RBAC worth having: change
-- what a role can do by editing rows here, without touching any user.
-- ----------------------------------------------------------------------------
CREATE TABLE role_permissions (
    role_id       INTEGER NOT NULL REFERENCES roles(role_id) ON DELETE CASCADE,
    permission_id INTEGER NOT NULL REFERENCES permissions(permission_id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

-- The composite PK gives a (role_id, permission_id) index for free, which
-- serves lookups starting from a role. The reverse direction -- "which roles
-- grant permission X" -- is an admin/audit query and needs its own index:
CREATE INDEX idx_role_permissions_permission ON role_permissions(permission_id);


-- ----------------------------------------------------------------------------
-- channel_memberships
--
-- Channel-scoped role assignment. ONE ROLE PER CHANNEL PER USER -- that is what
-- the composite primary key (channel_id, user_id) enforces.
-- ----------------------------------------------------------------------------
CREATE TABLE channel_memberships (
    channel_id UUID        NOT NULL REFERENCES channels(channel_id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    role_id    INTEGER     NOT NULL REFERENCES roles(role_id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (channel_id, user_id)
);

-- "Which channels does this user have a role on?" -- the PK's index leads with
-- channel_id, so a user-first lookup needs its own index.
CREATE INDEX idx_channel_memberships_user ON channel_memberships(user_id);

-- THE ONE-ROLE DECISION, AND HOW TO DEFEND IT:
--   Because the PK is (channel_id, user_id) and excludes role_id, a user holds
--   exactly one role per channel. To give someone both creator and moderator
--   abilities, you define a role that has both permission sets -- you do not
--   stack two rows.
--
--   The trade-off you are accepting: less compositional flexibility. The
--   trade-off you are buying: every permission check has exactly one role to
--   resolve, and "why can this person do X" has a single unambiguous answer.
--   GitHub works the same way -- one permission level per repository, not a
--   stack of them. If you later need composition, changing the PK to
--   (channel_id, user_id, role_id) is a small migration.
--
--   NOTE: channels.user_id already records the owner. Membership does not
--   replace that -- ownership is a property of the channel, membership is an
--   access grant. You will likely want application logic (or a trigger) that
--   auto-inserts an OWNER membership row when a channel is created, so
--   permission checks have a single code path instead of special-casing the
--   owner everywhere.


-- ----------------------------------------------------------------------------
-- platform_role_assignments
--
-- Platform-scoped role assignment. No channel_id -- these roles apply globally.
-- Multiple platform roles per user ARE allowed here (role_id is in the PK),
-- since global roles compose more naturally than channel roles do.
-- ----------------------------------------------------------------------------
CREATE TABLE platform_role_assignments (
    user_id    UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    role_id    INTEGER     NOT NULL REFERENCES roles(role_id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id)
);

-- WHY TWO TABLES INSTEAD OF ONE WITH A NULLABLE channel_id:
--   The single-table version needs channel_id NULL to mean "this role is
--   global". That is a footgun for three concrete reasons:
--
--   1. UNIQUE constraints treat NULLs as distinct, so a nullable channel_id
--      cannot cleanly enforce "one platform role assignment per user per role".
--   2. Every permission query must special-case the NULL, and forgetting the
--      `IS NULL` in one WHERE clause silently grants channel-scoped authority
--      globally -- an authorization bug that fails open, the worst kind.
--   3. The FK cannot express "channel_id is required for CHANNEL-scoped roles
--      and forbidden for PLATFORM-scoped ones".
--
--   Splitting makes the scope distinction structural. It is impossible to
--   write a channel-scoped grant into the platform table, because the column
--   does not exist there. The schema enforces what a comment would otherwise
--   have to.


-- ============================================================================
-- SECTION 3: SEED DATA
-- ============================================================================

INSERT INTO permissions (permission_key, description) VALUES
    ('video.upload',         'Upload a new video to a channel'),
    ('video.edit',           'Edit metadata of a video on this channel'),
    ('video.delete',         'Delete a video on this channel'),
    ('video.view_private',   'View private videos of this channel'),
    ('comment.create',       'Post a comment'),
    ('comment.moderate',     'Hide or remove comments on this channel'),
    ('channel.edit',         'Edit channel settings'),
    ('channel.manage_roles', 'Grant or revoke roles on this channel'),
    ('platform.takedown',    'Take down ANY video across the platform'),
    ('platform.ban_user',    'Suspend a user account platform-wide'),
    ('platform.review_queue','Access the moderation report queue');

INSERT INTO roles (role_name, scope, description) VALUES
    ('viewer',             'CHANNEL',  'Default; can watch public videos and comment'),
    ('creator',            'CHANNEL',  'Can upload and manage videos on this channel'),
    ('channel_moderator',  'CHANNEL',  'Can moderate comments on this channel'),
    ('owner',              'CHANNEL',  'Full control of this channel including role grants'),
    ('platform_moderator', 'PLATFORM', 'Can take down content and review reports globally'),
    ('platform_admin',     'PLATFORM', 'Unrestricted platform authority');

-- Wire up channel-scoped roles.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r
JOIN permissions p ON (
       (r.role_name = 'viewer'            AND p.permission_key IN ('comment.create'))
    OR (r.role_name = 'creator'           AND p.permission_key IN (
            'video.upload', 'video.edit', 'video.delete',
            'video.view_private', 'comment.create'))
    OR (r.role_name = 'channel_moderator' AND p.permission_key IN (
            'comment.create', 'comment.moderate'))
    OR (r.role_name = 'owner'             AND p.permission_key IN (
            'video.upload', 'video.edit', 'video.delete', 'video.view_private',
            'comment.create', 'comment.moderate',
            'channel.edit', 'channel.manage_roles'))
);

-- Wire up platform-scoped roles.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM roles r
JOIN permissions p ON (
       (r.role_name = 'platform_moderator' AND p.permission_key IN (
            'platform.takedown', 'platform.review_queue', 'comment.moderate'))
    OR (r.role_name = 'platform_admin')     -- admin gets everything
);


-- ============================================================================
-- SECTION 4: THE PERMISSION CHECK
-- ============================================================================
--
-- "May user U perform permission P on a resource owned by channel C?"
-- True if EITHER their channel-scoped role grants it OR a platform role does.
--
--   SELECT EXISTS (
--       SELECT 1
--       FROM channel_memberships cm
--       JOIN role_permissions rp ON rp.role_id = cm.role_id
--       JOIN permissions p       ON p.permission_id = rp.permission_id
--       WHERE cm.user_id = :user_id
--         AND cm.channel_id = :channel_id
--         AND p.permission_key = :permission_key
--
--       UNION ALL
--
--       SELECT 1
--       FROM platform_role_assignments pra
--       JOIN role_permissions rp ON rp.role_id = pra.role_id
--       JOIN permissions p       ON p.permission_id = rp.permission_id
--       WHERE pra.user_id = :user_id
--         AND p.permission_key = :permission_key
--   );
--
-- UNION ALL rather than UNION: we only care whether any row exists, so
-- deduplicating is wasted work.
--
-- THE PROBLEM WITH RUNNING THIS PER REQUEST:
--   That is three joins on every authorization decision, and authorization
--   happens on nearly every endpoint. At playback volume this becomes one of
--   the hottest queries in the system.
--
--   The standard fix is to resolve a user's full permission set once at login
--   and carry it -- either in the JWT claims, or cached in Redis keyed by
--   (user_id, channel_id) with a short TTL.
--
--   The catch, and it is the interesting part: both approaches make revocation
--   asynchronous. Strip someone's moderator role and they keep it until the
--   token expires or the cache entry does. For a moderator being removed for
--   abuse, that window matters. Mitigations are a short TTL, a token version
--   column on users that invalidates issued tokens, or a denylist checked on
--   sensitive operations only.
--
--   This schema does not solve that -- it is an application-layer decision.
--   But knowing the trade-off exists is most of the answer if you are asked.
--
--
-- KNOWN GAPS -- deliberately out of scope for V1, listed so they are not
-- mistaken for oversights:
--   * subscriptions            (channels.subscriber_count has no source table)
--   * video_reactions          (like_count/dislike_count have no source table)
--   * watch_events             (belongs in ClickHouse, not here)
--   * playlists, notifications, moderation reports / strikes
--   * partitioning on any high-volume table
-- ============================================================================