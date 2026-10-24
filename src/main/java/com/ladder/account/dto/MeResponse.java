package com.ladder.account.dto;

import java.util.List;
import java.util.UUID;

public record MeResponse(UUID userId, String email, String userName, String userHandle, List<ChannelSummary> channels) {

    public record ChannelSummary(UUID channelId, String channelName, String channelHandle) {
    }
}
