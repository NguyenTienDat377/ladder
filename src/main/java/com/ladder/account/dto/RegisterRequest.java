package com.ladder.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 320) String email,
        // A cheap upper bound on characters. BCrypt's real limit is 72 BYTES,
        // which differs for non-ASCII text, so AccountService checks that too.
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 100) String userName,
        // Lowercase on purpose: handles are compared as written, and two
        // spellings of one name must not both exist.
        @NotBlank @Pattern(regexp = "^[a-z0-9_]{3,50}$",
                message = "3-50 characters: lowercase letters, digits and underscores") String handle) {
}
