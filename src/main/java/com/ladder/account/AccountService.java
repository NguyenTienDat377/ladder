package com.ladder.account;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.ladder.account.dto.MeResponse;
import com.ladder.account.dto.MeResponse.ChannelSummary;
import com.ladder.account.dto.RegisterRequest;

// JdbcTemplate, not JPA entities: three INSERTs and one read, and the schema
// already has the constraints. An entity per table would be more code than the
// SQL it replaces.
@Service
public class AccountService {
    private static final int MAX_PASSWORD_BYTES = 72;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public AccountService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    // One transaction: a user without a channel (or a channel without its
    // owner row) would be a half-made account, so all three rows or none.
    @Transactional
    public void register(RegisterRequest request) {
        // BCrypt reads at most 72 BYTES, and the encoder refuses longer input.
        // @Size counts characters, which isn't the same thing: 30 Vietnamese
        // characters with diacritics are 90 bytes. Checked here so it's a
        // 400 with a reason, not a 500.
        if (request.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "password is too long (at most 72 bytes; accented characters count for 2 or 3)");
        }
        // Emails are stored lowercase and looked up lowercase, so
        // "A@x.com" and "a@x.com" can't be two accounts.
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        try {
            UUID userId = jdbc.queryForObject("""
                    INSERT INTO users (email, user_name, user_handle, password_hash)
                    VALUES (?, ?, ?, ?) RETURNING user_id
                    """, UUID.class, email, request.userName().trim(), request.handle(),
                    passwordEncoder.encode(request.password()));

            // A default channel named after the user, same handle.
            UUID channelId = jdbc.queryForObject("""
                    INSERT INTO channels (user_id, channel_name, channel_handle)
                    VALUES (?, ?, ?) RETURNING channel_id
                    """, UUID.class, userId, request.userName().trim(), request.handle());

            // The creator becomes the channel's owner through the same table
            // every other membership uses, so permission checks have one path.
            // Naming 'owner' here ASSIGNS a role; it isn't a permission check.
            int inserted = jdbc.update("""
                    INSERT INTO channel_memberships (channel_id, user_id, role_id)
                    SELECT ?, ?, role_id FROM roles WHERE role_name = 'owner'
                    """, channelId, userId);
            if (inserted != 1) {
                throw new IllegalStateException("role 'owner' is missing from the roles table");
            }
        } catch (DuplicateKeyException e) {
            // Email, user handle or channel handle already exists. One message
            // for all three, but this still lets a caller learn whether an email
            // is registered (try it with a fresh handle). Registration has to say
            // when it can't proceed, so that's accepted; the real defence is
            // rate limiting, which isn't built.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email or handle already taken");
        }
    }

    public MeResponse me(UUID userId) {
        List<ChannelSummary> channels = jdbc.query(
                "SELECT channel_id, channel_name, channel_handle FROM channels WHERE user_id = ? ORDER BY created_at",
                (rs, row) -> new ChannelSummary(rs.getObject("channel_id", UUID.class),
                        rs.getString("channel_name"), rs.getString("channel_handle")),
                userId);
        return jdbc.query("SELECT email, user_name, user_handle FROM users WHERE user_id = ?",
                        (rs, row) -> new MeResponse(userId, rs.getString("email"), rs.getString("user_name"),
                                rs.getString("user_handle"), channels),
                        userId)
                .stream().findFirst()
                // Logged in, but the row is gone (deleted since): treat as logged out.
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
