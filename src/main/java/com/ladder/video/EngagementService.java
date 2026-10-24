package com.ladder.video;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.ladder.video.dto.ReactionResponse;
import com.ladder.video.dto.SubscribedChannel;
import com.ladder.video.dto.SubscriptionResponse;

/**
 * Subscribing and liking. The tables (subscriptions, video_reactions) are the
 * source of truth; channels.subscriber_count and videos.like_count /
 * dislike_count are caches recomputed from them in the SAME transaction as the
 * write, so a reader never sees a row without its count or the reverse.
 *
 * Why recount instead of "+1 / -1": a recount is exact and heals any drift,
 * and it makes every write idempotent (repeating a PUT recomputes the same
 * number). View counts take the Redis route because a view happens thousands
 * of times a second; a subscribe or a like is a deliberate click.
 *
 * ponytail: every write first locks the parent row (the channel or the video).
 * Under READ COMMITTED each statement takes its own snapshot, and when a
 * recount runs into a row another transaction is changing, Postgres re-checks
 * only that target row, never the count subquery, so two racing recounts can
 * each miss the other's insert and store a wrong number. Taking the parent row
 * lock first makes the second writer wait until the first has committed, so
 * its recount starts from a snapshot that includes it. The price: writes to
 * ONE hot channel or video run one at a time. The ceiling is a viral video;
 * the upgrade is the Redis path view counts already use. FOR NO KEY UPDATE
 * (not FOR UPDATE) because it doesn't conflict with the foreign keys'
 * KEY SHARE locks, so the INSERTs themselves aren't blocked by it.
 */
@Service
public class EngagementService {
    private static final int SUBSCRIPTION_LIST_LIMIT = 100;

    private final JdbcTemplate jdbc;
    private final VideoAccess videoAccess;

    public EngagementService(JdbcTemplate jdbc, VideoAccess videoAccess) {
        this.jdbc = jdbc;
        this.videoAccess = videoAccess;
    }

    @Transactional
    public SubscriptionResponse subscribe(String handle, UUID userId) {
        LockedChannel channel = lockChannel(handle);
        // 409, not 403: the caller isn't missing a permission, the request
        // conflicts with the state of things (it is their own channel).
        if (channel.ownerId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "you can't subscribe to your own channel");
        }
        jdbc.update("INSERT INTO subscriptions (subscriber_id, channel_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                userId, channel.channelId());
        return new SubscriptionResponse(true, recountSubscribers(channel.channelId()));
    }

    @Transactional
    public SubscriptionResponse unsubscribe(String handle, UUID userId) {
        LockedChannel channel = lockChannel(handle);
        jdbc.update("DELETE FROM subscriptions WHERE subscriber_id = ? AND channel_id = ?", userId, channel.channelId());
        return new SubscriptionResponse(false, recountSubscribers(channel.channelId()));
    }

    @Transactional
    public ReactionResponse react(UUID videoId, UUID userId, String reaction) {
        videoAccess.requireWatchable(videoId, userId); // nobody reacts to a video they can't see
        lockVideo(videoId);
        // One row per (video, user): switching LIKE <-> DISLIKE rewrites it.
        jdbc.update("""
                INSERT INTO video_reactions (video_id, user_id, reaction) VALUES (?, ?, ?)
                ON CONFLICT (video_id, user_id) DO UPDATE SET reaction = EXCLUDED.reaction
                """, videoId, userId, reaction);
        return recountReactions(videoId, reaction);
    }

    @Transactional
    public ReactionResponse unreact(UUID videoId, UUID userId) {
        videoAccess.requireWatchable(videoId, userId);
        lockVideo(videoId);
        jdbc.update("DELETE FROM video_reactions WHERE video_id = ? AND user_id = ?", videoId, userId);
        return recountReactions(videoId, null);
    }

    public List<SubscribedChannel> subscriptions(UUID userId) {
        // ponytail: a hard cap, no paging. Add keyset paging when someone subscribes to 100+ channels.
        return jdbc.query("""
                SELECT c.channel_id, c.channel_name, c.channel_handle, c.subscriber_count
                FROM subscriptions s JOIN channels c ON c.channel_id = s.channel_id
                WHERE s.subscriber_id = ?
                ORDER BY s.created_at DESC
                LIMIT ?
                """,
                (rs, row) -> new SubscribedChannel(rs.getObject("channel_id", UUID.class), rs.getString("channel_name"),
                        rs.getString("channel_handle"), rs.getLong("subscriber_count")),
                userId, SUBSCRIPTION_LIST_LIMIT);
    }

    private record LockedChannel(UUID channelId, UUID ownerId) {
    }

    private LockedChannel lockChannel(String handle) {
        return jdbc.query("SELECT channel_id, user_id FROM channels WHERE channel_handle = ? FOR NO KEY UPDATE",
                        (rs, row) -> new LockedChannel(rs.getObject("channel_id", UUID.class), rs.getObject("user_id", UUID.class)),
                        handle)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "channel not found"));
    }

    private void lockVideo(UUID videoId) {
        // Empty = deleted since requireWatchable looked at it.
        if (jdbc.queryForList("SELECT 1 FROM videos WHERE video_id = ? FOR NO KEY UPDATE", videoId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "video not found");
        }
    }

    private long recountSubscribers(UUID channelId) {
        Long count = jdbc.queryForObject("""
                UPDATE channels SET subscriber_count = (SELECT count(*) FROM subscriptions WHERE channel_id = ?)
                WHERE channel_id = ? RETURNING subscriber_count
                """, Long.class, channelId, channelId);
        return count == null ? 0 : count;
    }

    private ReactionResponse recountReactions(UUID videoId, String myReaction) {
        return jdbc.queryForObject("""
                UPDATE videos v SET like_count = c.likes, dislike_count = c.dislikes
                FROM (SELECT count(*) FILTER (WHERE reaction = 'LIKE')    AS likes,
                             count(*) FILTER (WHERE reaction = 'DISLIKE') AS dislikes
                      FROM video_reactions WHERE video_id = ?) c
                WHERE v.video_id = ?
                RETURNING v.like_count, v.dislike_count
                """,
                (rs, row) -> new ReactionResponse(myReaction, rs.getLong("like_count"), rs.getLong("dislike_count")),
                videoId, videoId);
    }
}
