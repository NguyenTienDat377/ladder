package com.ladder.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.ladder.video.VideoAccess;

import io.minio.MinioClient;
import jakarta.servlet.http.Cookie;

// The whole security filter chain against a real database: what matters here is
// who gets 401/403/404, which only the real chain and real tables can tell.
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "ladder.views.flush-interval=PT1H"
})
class AccountSecurityTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:8").withExposedPorts(6379);

    @MockitoBean
    MinioClient minioClient;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired VideoAccess videoAccess;

    private static final String PASSWORD = "correct horse battery";

    @Test
    void registerCreatesUserChannelAndOwnerMembership() throws Exception {
        String tag = tag();
        register(tag).andExpect(status().isCreated());

        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email(tag));
        assertThat(hash).startsWith("$2").isNotEqualTo(PASSWORD); // BCrypt, never the password itself

        String role = jdbc.queryForObject("""
                SELECT r.role_name FROM channel_memberships cm
                JOIN channels c ON c.channel_id = cm.channel_id
                JOIN roles r ON r.role_id = cm.role_id
                WHERE c.channel_handle = ?
                """, String.class, handle(tag));
        assertThat(role).isEqualTo("owner");
    }

    @Test
    void duplicateEmailOrHandleReturns409() throws Exception {
        String tag = tag();
        register(tag).andExpect(status().isCreated());

        register(email(tag), "u2" + tag, "other_" + tag).andExpect(status().isConflict()); // same email
        register("other" + email(tag), PASSWORD, handle(tag)).andExpect(status().isConflict()); // same handle
        // Email case must not make a second account.
        register(email(tag).toUpperCase(), PASSWORD, "third_" + tag).andExpect(status().isConflict());

        // The failed attempts left nothing behind (the transaction rolled back).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE user_handle LIKE ?", Integer.class,
                "%" + tag)).isEqualTo(1);
    }

    @Test
    void invalidInputReturns400() throws Exception {
        String tag = tag();
        register("not-an-email", PASSWORD, handle(tag)).andExpect(status().isBadRequest());
        register(email(tag), "short", handle(tag)).andExpect(status().isBadRequest());
        register(email(tag), PASSWORD, "Upper-Case!").andExpect(status().isBadRequest());
    }

    @Test
    void passwordOver72BytesIsRejectedWith400NotAServerError() throws Exception {
        // 30 characters but 90 bytes in UTF-8: passes a character-count limit,
        // but BCrypt only reads 72 bytes and refuses longer input.
        String longInBytes = "\u1ea1".repeat(30);
        assertThat(longInBytes.length()).isLessThan(72);
        assertThat(longInBytes.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(72);

        register(email(tag()), longInBytes, handle(tag())).andExpect(status().isBadRequest());
    }

    @Test
    void loginWithAnOverlongPasswordIsJust401() throws Exception {
        String tag = tag();
        register(tag).andExpect(status().isCreated());

        login(email(tag), "x".repeat(200)).andExpect(status().isUnauthorized());
        login(email(tag), "\u1ea1".repeat(30)).andExpect(status().isUnauthorized());
    }

    @Test
    void createVideoCanChooseVisibilityAndDefaultsToPrivate() throws Exception {
        String tag = tag();
        register(tag).andExpect(status().isCreated());
        MockHttpSession session = loginSession(tag);
        String channel = channelOf(tag).toString();

        mvc.perform(post("/api/v1/videos").session(session).with(csrfToken()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"channelId\":\"" + channel + "\",\"title\":\"a\",\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/videos").session(session).with(csrfToken()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"channelId\":\"" + channel + "\",\"title\":\"b\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/videos").session(session).with(csrfToken()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"channelId\":\"" + channel + "\",\"title\":\"c\",\"visibility\":\"SECRET\"}"))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT visibility FROM videos WHERE channel_id = ?::uuid AND title = 'a'",
                String.class, channel)).isEqualTo("PUBLIC");
        assertThat(jdbc.queryForObject("SELECT visibility FROM videos WHERE channel_id = ?::uuid AND title = 'b'",
                String.class, channel)).isEqualTo("PRIVATE");
    }

    @Test
    void wrongPasswordAndUnknownEmailBothReturn401() throws Exception {
        String tag = tag();
        register(tag).andExpect(status().isCreated());

        login(email(tag), "wrong password!").andExpect(status().isUnauthorized());
        login("nobody" + email(tag), PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void userWithoutPasswordCannotLogIn() throws Exception {
        String tag = tag();
        // A user from before login existed: no password_hash.
        jdbc.update("INSERT INTO users (email, user_name, user_handle) VALUES (?, 'Old', ?)", email(tag), handle(tag));

        login(email(tag), "").andExpect(status().isUnauthorized());
        login(email(tag), PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void loginThenMeReturnsUserAndChannel() throws Exception {
        String tag = tag();
        register(tag).andExpect(status().isCreated());

        MockHttpSession session = loginSession(tag);

        mvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userHandle").value(handle(tag)))
                .andExpect(jsonPath("$.email").value(email(tag)))
                .andExpect(jsonPath("$.channels[0].channelHandle").value(handle(tag)));
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        String tag = tag();
        register(tag).andExpect(status().isCreated());
        MockHttpSession session = loginSession(tag);

        mvc.perform(post("/api/v1/auth/logout").session(session).with(csrfToken())).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void meAnonymousReturns401AndCreatesNoServerSession() throws Exception {
        var request = mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized())
                .andReturn().getRequest();
        // Every page load asks /me; an anonymous visitor must not cost a session each time.
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void postWithoutCsrfTokenReturns403() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(email(tag()), PASSWORD, "nocsrf" + tag())))
                .andExpect(status().isForbidden());
    }

    @Test
    void csrfCookieIsIssuedToABrowserThatJustLoadsAPage() throws Exception {
        // The frontend can only send X-XSRF-TOKEN if it can read this cookie
        // after an ordinary GET.
        mvc.perform(get("/api/v1/auth/me")).andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void uploadsNeedLoginAndPermissionOnTheChannel() throws Exception {
        String a = tag();
        String b = tag();
        register(a).andExpect(status().isCreated());
        register(b).andExpect(status().isCreated());
        UUID channelOfA = channelOf(a);
        String body = "{\"channelId\":\"" + channelOfA + "\",\"title\":\"hello\"}";

        // Anonymous: 401.
        mvc.perform(post("/api/v1/videos").with(csrfToken()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        // Someone else's channel: 403.
        mvc.perform(post("/api/v1/videos").session(loginSession(b)).with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        // Own channel: 201.
        mvc.perform(post("/api/v1/videos").session(loginSession(a)).with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        // Finishing someone else's upload is refused too.
        UUID video = seedVideo(channelOfA, "UPLOADING", "PRIVATE");
        mvc.perform(post("/api/v1/videos/" + video + "/complete").session(loginSession(b)).with(csrfToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthIsReachableAnonymously() throws Exception {
        // 200 normally; 503 here because the test has no RabbitMQ. Either way
        // the security chain let it through, which is what this checks.
        int code = mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();
        assertThat(code).isIn(200, 503);
    }

    @Test
    void privateVideoIsHiddenFromAnonymousAndStrangersButNotItsOwner() throws Exception {
        String owner = tag();
        String stranger = tag();
        register(owner).andExpect(status().isCreated());
        register(stranger).andExpect(status().isCreated());
        UUID channel = channelOf(owner);
        UUID privateVideo = seedVideo(channel, "READY", "PRIVATE");
        UUID publicVideo = seedVideo(channel, "READY", "PUBLIC");
        UUID unlisted = seedVideo(channel, "READY", "UNLISTED");

        // Every refusal is a 404: nothing reveals the private video exists.
        assertNotFound(privateVideo, null);
        assertNotFound(privateVideo, userId(stranger));
        assertThat(videoAccess.requireWatchable(privateVideo, userId(owner)).getVideoId()).isEqualTo(privateVideo);

        assertThat(videoAccess.requireWatchable(publicVideo, null)).isNotNull();
        assertThat(videoAccess.requireWatchable(unlisted, null)).isNotNull();
        // Not READY is hidden even from the owner.
        assertNotFound(seedVideo(channel, "PROCESSING", "PUBLIC"), userId(owner));

        // And over HTTP the same video answers 404 to an anonymous caller, not 401.
        mvc.perform(get("/api/v1/videos/" + privateVideo + "/master.m3u8")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/videos/" + privateVideo + "/view").with(csrfToken())).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- helpers

    // What the real frontend does: GET anything to receive the XSRF-TOKEN
    // cookie, then send that value back in the X-XSRF-TOKEN header (and the
    // cookie). Deliberately not Spring's csrf() helper: it permanently swaps
    // the CSRF token repository on the shared filter chain, which would hide
    // the cookie from every test that runs after it.
    private RequestPostProcessor csrfToken() {
        return request -> {
            try {
                Cookie issued = mvc.perform(get("/api/v1/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
                assertThat(issued).isNotNull();
                request.setCookies(issued);
                request.addHeader("X-XSRF-TOKEN", issued.getValue());
                return request;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private void assertNotFound(UUID video, UUID user) {
        assertThatThrownBy(() -> videoAccess.requireWatchable(video, user))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String email(String tag) {
        return tag + "@test.dev";
    }

    private static String handle(String tag) {
        return "u_" + tag;
    }

    private static String json(String email, String password, String handle) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"userName\":\"Test\",\"handle\":\""
                + handle + "\"}";
    }

    private org.springframework.test.web.servlet.ResultActions register(String tag) throws Exception {
        return register(email(tag), PASSWORD, handle(tag));
    }

    private org.springframework.test.web.servlet.ResultActions register(String email, String password, String handle)
            throws Exception {
        return mvc.perform(post("/api/v1/auth/register").with(csrfToken()).contentType(MediaType.APPLICATION_JSON)
                .content(json(email, password, handle)));
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").with(csrfToken())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("email", email).param("password", password));
    }

    private MockHttpSession loginSession(String tag) throws Exception {
        MockHttpSession session = (MockHttpSession) login(email(tag), PASSWORD)
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private UUID userId(String tag) {
        return jdbc.queryForObject("SELECT user_id FROM users WHERE email = ?", UUID.class, email(tag));
    }

    private UUID channelOf(String tag) {
        return jdbc.queryForObject("SELECT channel_id FROM channels WHERE channel_handle = ?", UUID.class, handle(tag));
    }

    private UUID seedVideo(UUID channel, String status, String visibility) {
        return jdbc.queryForObject(
                "INSERT INTO videos (channel_id, title, status, visibility) VALUES (?, 'v', ?, ?) RETURNING video_id",
                UUID.class, channel, status, visibility);
    }
}
