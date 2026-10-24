package com.ladder.security;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * "May user U do P on channel C?" Application code asks about permission KEYS
 * ("video.upload"), never role names, so adding a role is an INSERT and no
 * code changes.
 *
 * ponytail: runs the three-join query on every call, uncached (a known gap in
 * the README). Caching makes revocation asynchronous; decide that when this
 * shows up as a cost.
 */
@Component
public class Permissions {
    // True if EITHER the user's role on this channel OR a platform-wide role
    // grants the key. UNION ALL, not UNION: we only ask whether any row
    // exists, so deduplicating is wasted work.
    private static final String SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM channel_memberships cm
                JOIN role_permissions rp ON rp.role_id = cm.role_id
                JOIN permissions p       ON p.permission_id = rp.permission_id
                WHERE cm.user_id = ? AND cm.channel_id = ? AND p.permission_key = ?
                UNION ALL
                SELECT 1
                FROM platform_role_assignments pra
                JOIN role_permissions rp ON rp.role_id = pra.role_id
                JOIN permissions p       ON p.permission_id = rp.permission_id
                WHERE pra.user_id = ? AND p.permission_key = ?
            )
            """;

    private final JdbcTemplate jdbc;

    public Permissions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** False for an anonymous caller (null user): no identity, no permissions. */
    public boolean has(UUID userId, UUID channelId, String permissionKey) {
        if (userId == null) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(SQL, Boolean.class,
                userId, channelId, permissionKey, userId, permissionKey));
    }

    /** 403 unless allowed. For callers already logged in; anonymous ones are stopped earlier with 401. */
    public void require(UUID userId, UUID channelId, String permissionKey) {
        if (!has(userId, channelId, permissionKey)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not allowed");
        }
    }
}
