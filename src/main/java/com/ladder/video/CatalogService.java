package com.ladder.video;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.ladder.security.Permissions;
import com.ladder.video.dto.ChannelPageResponse;
import com.ladder.video.dto.VideoDetailsResponse;
import com.ladder.video.dto.VideoDetailsResponse.ChannelInfo;
import com.ladder.video.dto.VideoPage;
import com.ladder.video.dto.VideoPage.Item;
import com.ladder.video.dto.VideoUpdateRequest;
import com.ladder.video.dto.VideoUpdateResponse;

/**
 * Everything the watch page, the home feed and channel pages read, plus the
 * edit of a video's metadata.
 *
 * JdbcTemplate rather than JPA: these are read-only joins across videos and
 * channels that return partial rows, which the Video entity doesn't model. And
 * the edit must touch only the three fields it was given: a JPA save() would
 * rewrite every mapped column from a possibly stale copy, for example undoing
 * a status change the transcode worker made a moment ago.
 */
@Service
public class CatalogService {
    static final int DEFAULT_LIMIT = 24;
    static final int MAX_LIMIT = 50;
    // Sorts after every real UUIDv7, so "no cursor" can use the same
    // "video_id < ?" condition as every other page, and the partial index
    // idx_videos_feed applies. "? IS NULL OR video_id < ?" would defeat it.
    private static final UUID NO_CURSOR = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    private final JdbcTemplate jdbc;
    private final VideoAccess videoAccess;
    private final ViewCountService viewCounts;
    private final VideoRepository videos;
    private final Permissions permissions;

    public CatalogService(JdbcTemplate jdbc, VideoAccess videoAccess, ViewCountService viewCounts,
                          VideoRepository videos, Permissions permissions) {
        this.jdbc = jdbc;
        this.videoAccess = videoAccess;
        this.viewCounts = viewCounts;
        this.videos = videos;
        this.permissions = permissions;
    }

    /** @param userId the logged-in user, or null for an anonymous visitor */
    public VideoDetailsResponse details(UUID videoId, UUID userId) {
        // The access rule lives in one place; a refusal is already a 404.
        Video video = videoAccess.requireWatchable(videoId, userId);
        return jdbc.queryForObject("""
                SELECT v.title, v.description, v.visibility, v.status, v.created_at, v.duration_secs,
                       v.like_count, v.dislike_count,
                       c.channel_id, c.channel_name, c.channel_handle, c.subscriber_count,
                       r.reaction
                FROM videos v JOIN channels c ON c.channel_id = v.channel_id
                LEFT JOIN video_reactions r ON r.video_id = v.video_id AND r.user_id = ?::uuid
                WHERE v.video_id = ?
                """,
                (rs, row) -> new VideoDetailsResponse(videoId, rs.getString("title"), rs.getString("description"),
                        rs.getString("visibility"), rs.getString("status"), instant(rs.getObject("created_at", OffsetDateTime.class)),
                        (Integer) rs.getObject("duration_secs"),
                        // Stored count plus the views still waiting in Redis.
                        viewCounts.displayedCount(video), rs.getLong("like_count"), rs.getLong("dislike_count"),
                        new ChannelInfo(rs.getObject("channel_id", UUID.class), rs.getString("channel_name"),
                                rs.getString("channel_handle"), rs.getLong("subscriber_count")),
                        rs.getString("reaction")),
                userId, videoId);
    }

    /** The home feed: newest public videos first. */
    public VideoPage feed(UUID before, int limit) {
        int size = clamp(limit);
        // Keyset paging: "everything older than the last one you saw". The ids
        // are UUIDv7, so id order is creation order, and the page cost doesn't
        // grow the way OFFSET does. One extra row says whether another page exists.
        // The literal status/visibility conditions must match the partial index.
        List<Item> rows = jdbc.query("""
                SELECT v.video_id, v.title, v.created_at, v.duration_secs, v.view_count, v.status, v.visibility,
                       c.channel_name, c.channel_handle
                FROM videos v JOIN channels c ON c.channel_id = v.channel_id
                WHERE v.status = 'READY' AND v.visibility = 'PUBLIC' AND v.video_id < ?
                ORDER BY v.video_id DESC
                LIMIT ?
                """, ITEM, before == null ? NO_CURSOR : before, size + 1);
        return page(rows, size);
    }

    public ChannelPageResponse channel(String handle, UUID before, int limit, UUID userId) {
        record ChannelRow(UUID channelId, String name, String handle, long subscribers, boolean subscribed) {
        }
        // The EXISTS is false for an anonymous caller: a null id matches no row.
        ChannelRow channel = jdbc.query("""
                        SELECT channel_id, channel_name, channel_handle, subscriber_count,
                               EXISTS (SELECT 1 FROM subscriptions s
                                       WHERE s.channel_id = channels.channel_id AND s.subscriber_id = ?::uuid) AS subscribed
                        FROM channels WHERE channel_handle = ?
                        """,
                        (rs, row) -> new ChannelRow(rs.getObject("channel_id", UUID.class), rs.getString("channel_name"),
                                rs.getString("channel_handle"), rs.getLong("subscriber_count"), rs.getBoolean("subscribed")),
                        userId, handle)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "channel not found"));

        // Whoever may view this channel's private videos is its owner (or staff):
        // they get the management view, every video in every state. Everyone
        // else, including other logged-in users, gets only PUBLIC + READY.
        // UNLISTED is never listed for anyone but the owner: its point is that
        // only people holding the link can find it.
        boolean canManage = permissions.has(userId, channel.channelId(), "video.view_private");
        int size = clamp(limit);
        List<Item> rows = jdbc.query("""
                SELECT v.video_id, v.title, v.created_at, v.duration_secs, v.view_count, v.status, v.visibility,
                       c.channel_name, c.channel_handle
                FROM videos v JOIN channels c ON c.channel_id = v.channel_id
                WHERE v.channel_id = ? AND v.video_id < ?
                  AND (?::boolean OR (v.status = 'READY' AND v.visibility = 'PUBLIC'))
                ORDER BY v.video_id DESC
                LIMIT ?
                """, ITEM, channel.channelId(), before == null ? NO_CURSOR : before, canManage, size + 1);

        // A real count, not channels.video_count: nothing maintains that column.
        Long publicVideos = jdbc.queryForObject(
                "SELECT count(*) FROM videos WHERE channel_id = ? AND status = 'READY' AND visibility = 'PUBLIC'",
                Long.class, channel.channelId());

        return new ChannelPageResponse(channel.channelId(), channel.name(), channel.handle(), channel.subscribers(),
                publicVideos == null ? 0 : publicVideos, canManage, channel.subscribed(),
                page(rows, size));
    }

    /** Changes only the fields that were sent. Needs login (the caller) and video.edit on the video's channel. */
    public VideoUpdateResponse update(UUID videoId, VideoUpdateRequest request, UUID userId) {
        Video video = videos.findById(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "video not found"));
        permissions.require(userId, video.getChannelId(), "video.edit");

        // COALESCE(?, column): a field that wasn't sent is null and keeps its value.
        return jdbc.queryForObject("""
                UPDATE videos SET title = COALESCE(?, title), description = COALESCE(?, description),
                                  visibility = COALESCE(?, visibility)
                WHERE video_id = ?
                RETURNING video_id, title, description, visibility
                """,
                (rs, row) -> new VideoUpdateResponse(rs.getObject("video_id", UUID.class), rs.getString("title"),
                        rs.getString("description"), rs.getString("visibility")),
                request.title(), request.description(), request.visibility(), videoId);
    }

    private static final RowMapper<Item> ITEM = (rs, row) -> new Item(
            rs.getObject("video_id", UUID.class), rs.getString("title"),
            instant(rs.getObject("created_at", OffsetDateTime.class)), (Integer) rs.getObject("duration_secs"),
            // The stored count only: asking Redis for every row of a page would be one
            // round trip per video, and a feed number a minute behind is fine.
            rs.getLong("view_count"), rs.getString("channel_name"), rs.getString("channel_handle"),
            rs.getString("status"), rs.getString("visibility"), null);

    // Rows were fetched with limit + 1: the extra one only proves there's a next page.
    private static VideoPage page(List<Item> rows, int size) {
        if (rows.size() <= size) {
            return new VideoPage(rows, null);
        }
        List<Item> items = rows.subList(0, size);
        return new VideoPage(items, items.get(size - 1).videoId());
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    private static java.time.Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
