package com.ladder.video.dto;

import java.util.UUID;

public record VideoCreateResponse(UUID videoId, String uploadUrl) {
}
