package com.ladder.video.dto;

import java.time.Instant;
import java.util.UUID;

public record VideoDetailsResponse(
        UUID videoId, String title, String description, String visibility, String status,
        Instant createdAt, Integer durationSecs,
        long viewCount, long likeCount, long dislikeCount,
        ChannelInfo channel,
        // The caller's own reaction: "LIKE", "DISLIKE", or null (none, or anonymous).
        String myReaction) {

    public record ChannelInfo(UUID channelId, String channelName, String channelHandle, long subscriberCount) {
    }
}
