package com.ladder.video.dto;

import java.util.UUID;

public record VideoUpdateResponse(UUID videoId, String title, String description, String visibility) {
}
