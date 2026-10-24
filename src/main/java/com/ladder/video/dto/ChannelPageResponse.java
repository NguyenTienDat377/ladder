package com.ladder.video.dto;

import java.util.UUID;

public record ChannelPageResponse(
        UUID channelId, String channelName, String channelHandle,
        long subscriberCount, long publicVideoCount,
        // True when the caller may manage this channel: the list below then also holds its private and unfinished videos.
        boolean canManage,
        VideoPage videos) {
}
