package com.ladder.video.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Every field optional: null means "leave unchanged". Because of that a field
// can't be set back to null; send "" to clear the description.
public record VideoUpdateRequest(
        // Allows null (unchanged), rejects "" and whitespace-only.
        @Size(max = 200) @Pattern(regexp = "(?s).*\\S.*") String title,
        @Size(max = 5000) String description,
        @Pattern(regexp = "PUBLIC|UNLISTED|PRIVATE") String visibility) {
}
