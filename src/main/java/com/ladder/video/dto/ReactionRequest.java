package com.ladder.video.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ReactionRequest(@NotNull @Pattern(regexp = "LIKE|DISLIKE") String reaction) {
}
