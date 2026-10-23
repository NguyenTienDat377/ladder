package com.ladder.video.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record VideoCreateRequest(
        @NotNull UUID channelId,
        @NotBlank @Size(max = 200) String title) {
}
