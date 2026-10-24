-- V2: login, subscriptions, likes/dislikes, thumbnails.

-- Nullable on purpose: rows that predate login (the dev seed user) have no
-- password and simply can't log in, instead of getting a guessable default.
ALTER TABLE users ADD COLUMN password_hash VARCHAR(100);

-- Object key of the thumbnail JPEG, set by the transcode worker. NULL means
-- "no thumbnail (yet)": old videos, or a frame capture that failed.
ALTER TABLE videos ADD COLUMN thumbnail_path VARCHAR(500);

-- The source of truth behind channels.subscriber_count. One row per
-- (subscriber, channel): the composite PK makes a double click a no-op and
-- doubles as the "is this user subscribed?" index.
CREATE TABLE subscriptions (
    subscriber_id UUID        NOT NULL REFERENCES users(user_id)       ON DELETE CASCADE,
    channel_id    UUID        NOT NULL REFERENCES channels(channel_id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (subscriber_id, channel_id)
);
-- Counting a channel's subscribers (the PK's leading column is subscriber_id).
CREATE INDEX idx_subscriptions_channel ON subscriptions(channel_id);

-- Source of truth behind videos.like_count / dislike_count. PK (video_id,
-- user_id) = one reaction per user per video; switching like -> dislike is an
-- UPDATE of that row, never a second row.
CREATE TABLE video_reactions (
    video_id   UUID        NOT NULL REFERENCES videos(video_id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users(user_id)   ON DELETE CASCADE,
    reaction   VARCHAR(10) NOT NULL CHECK (reaction IN ('LIKE', 'DISLIKE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (video_id, user_id)
);

-- The home feed: newest public videos first. Partial index, so private,
-- unlisted and half-processed videos cost nothing here. UUIDv7 ids sort by
-- creation time, so paging by video_id is paging by age.
CREATE INDEX idx_videos_feed ON videos (video_id DESC)
    WHERE status = 'READY' AND visibility = 'PUBLIC';
CREATE INDEX idx_videos_channel ON videos (channel_id, video_id DESC);
