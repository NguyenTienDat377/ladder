package com.ladder.video.dto;

import java.util.UUID;

public record SubscribedChannel(UUID channelId, String channelName, String channelHandle, long subscriberCount) {
}
