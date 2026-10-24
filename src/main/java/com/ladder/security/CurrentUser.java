package com.ladder.security;

import java.security.Principal;
import java.util.UUID;

// The login principal's name IS the user id (see SecurityConfig), so there is
// no lookup and no custom principal class.
public final class CurrentUser {
    private CurrentUser() {
    }

    /** The logged-in user's id, or null for an anonymous request. */
    public static UUID idOrNull(Principal principal) {
        return principal == null ? null : UUID.fromString(principal.getName());
    }
}
