package com.ladder.video.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One page of videos. {@code nextBefore} is the {@code before} value for the next page; null on the last one. */
public record VideoPage(List<Item> items, UUID nextBefore) {

    public record Item(
            UUID videoId, String title, Instant createdAt, Integer durationSecs, long viewCount,
            String channelName, String channelHandle,
            // Only the owner's own listing can contain non-public or unfinished videos.
            String status, String visibility,
            // Relative URL of the picture, or null (no thumbnail yet, or not READY).
            String thumbnailUrl) {
    }
}
