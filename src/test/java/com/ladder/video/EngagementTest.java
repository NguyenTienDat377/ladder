package com.ladder.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.minio.MinioClient;
import jakarta.servlet.http.Cookie;

// Subscribe and like/dislike against a real database. Anonymous calls still
// send a CSRF token: without one the CSRF filter answers 403 before the
// "not logged in" 401 this wants to check.
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "ladder.views.flush-interval=PT1H"
})
class EngagementTest {

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

    private static final String PASSWORD = "correct horse battery";

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM videos");
    }

    @Test
    void subscribeIsIdempotentAndCountsTheSubscriber() throws Exception {
        Fixture f = fixture();

        subscribe(f.fan, f.channelHandle).andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(true))
                .andExpect(jsonPath("$.subscriberCount").value(1));
        subscribe(f.fan, f.channelHandle).andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriberCount").value(1));

        assertThat(subscriptionRows(f.channelId)).isEqualTo(1);
        assertThat(storedSubscriberCount(f.channelId)).isEqualTo(1);
    }

    @Test
    void unsubscribeRemovesTheRowAndRepeatingItIsHarmless() throws Exception {
        Fixture f = fixture();
        subscribe(f.fan, f.channelHandle).andExpect(status().isOk());

        unsubscribe(f.fan, f.channelHandle).andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(false))
                .andExpect(jsonPath("$.subscriberCount").value(0));
        unsubscribe(f.fan, f.channelHandle).andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriberCount").value(0));

        assertThat(subscriptionRows(f.channelId)).isZero();
    }

    @Test
    void cannotSubscribeToYourOwnChannel() throws Exception {
        Fixture f = fixture();

        subscribe(f.owner, f.channelHandle).andExpect(status().isConflict());

        assertThat(subscriptionRows(f.channelId)).isZero();
        assertThat(storedSubscriberCount(f.channelId)).isZero();
    }

    @Test
    void subscribingNeedsLoginAndAnExistingChannel() throws Exception {
        Fixture f = fixture();

        mvc.perform(put("/api/v1/channels/" + f.channelHandle + "/subscription").with(csrfToken()))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/channels/" + f.channelHandle + "/subscription").with(csrfToken()))
                .andExpect(status().isUnauthorized());
        subscribe(f.fan, "nobody_" + tag()).andExpect(status().isNotFound());
        // And no token at all is refused before anything else.
        mvc.perform(put("/api/v1/channels/" + f.channelHandle + "/subscription").session(f.fan))
                .andExpect(status().isForbidden());
    }

    @Test
    void channelPageShowsWhetherTheCallerSubscribes() throws Exception {
        Fixture f = fixture();
        String url = "/api/v1/channels/" + f.channelHandle;
        subscribe(f.fan, f.channelHandle).andExpect(status().isOk());

        mvc.perform(get(url)).andExpect(jsonPath("$.subscribed").value(false))
                .andExpect(jsonPath("$.subscriberCount").value(1));
        mvc.perform(get(url).session(f.fan)).andExpect(jsonPath("$.subscribed").value(true));
        mvc.perform(get(url).session(f.owner)).andExpect(jsonPath("$.subscribed").value(false));
    }

    @Test
    void subscriptionsListIsNewestFirstAndNeedsLogin() throws Exception {
        Fixture a = fixture();
        Fixture b = fixture();
        MockHttpSession fan = login(tag());
        subscribe(fan, a.channelHandle).andExpect(status().isOk());
        subscribe(fan, b.channelHandle).andExpect(status().isOk());

        mvc.perform(get("/api/v1/subscriptions").session(fan))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].channelHandle").value(b.channelHandle))
                .andExpect(jsonPath("$[1].channelHandle").value(a.channelHandle));
        mvc.perform(get("/api/v1/subscriptions")).andExpect(status().isUnauthorized());
    }

    @Test
    void likeThenDislikeSwitchesTheOneRowAndBothCounts() throws Exception {
        Fixture f = fixture();

        react(f.fan, f.video, "LIKE").andExpect(status().isOk())
                .andExpect(jsonPath("$.myReaction").value("LIKE"))
                .andExpect(jsonPath("$.likeCount").value(1))
                .andExpect(jsonPath("$.dislikeCount").value(0));
        react(f.fan, f.video, "DISLIKE").andExpect(status().isOk())
                .andExpect(jsonPath("$.myReaction").value("DISLIKE"))
                .andExpect(jsonPath("$.likeCount").value(0))
                .andExpect(jsonPath("$.dislikeCount").value(1));

        assertThat(reactionRows(f.video)).isEqualTo(1);
        assertThat(storedCounts(f.video)).containsExactly(0L, 1L);
    }

    @Test
    void repeatingTheSameReactionChangesNothing() throws Exception {
        Fixture f = fixture();

        react(f.fan, f.video, "LIKE").andExpect(status().isOk());
        react(f.fan, f.video, "LIKE").andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount").value(1));

        assertThat(reactionRows(f.video)).isEqualTo(1);
    }

    @Test
    void deletingAReactionRemovesItAndRepeatingTheDeleteIsHarmless() throws Exception {
        Fixture f = fixture();
        react(f.fan, f.video, "LIKE").andExpect(status().isOk());

        unreact(f.fan, f.video).andExpect(status().isOk())
                .andExpect(jsonPath("$.myReaction").isEmpty())
                .andExpect(jsonPath("$.likeCount").value(0));
        unreact(f.fan, f.video).andExpect(status().isOk());

        assertThat(reactionRows(f.video)).isZero();
    }

    @Test
    void countsAddUpAcrossUsers() throws Exception {
        Fixture f = fixture();
        react(f.fan, f.video, "LIKE").andExpect(status().isOk());
        react(login(tag()), f.video, "LIKE").andExpect(status().isOk());
        react(login(tag()), f.video, "LIKE").andExpect(status().isOk());
        react(login(tag()), f.video, "DISLIKE").andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount").value(3))
                .andExpect(jsonPath("$.dislikeCount").value(1));

        assertThat(storedCounts(f.video)).containsExactly(3L, 1L);
    }

    @Test
    void cannotReactToAVideoYouCannotSee() throws Exception {
        Fixture f = fixture();
        UUID privateVideo = seedVideo(f.channelId, "READY", "PRIVATE");
        UUID processing = seedVideo(f.channelId, "PROCESSING", "PUBLIC");

        // A stranger gets 404 (the video "doesn't exist" for them), never 403.
        react(f.fan, privateVideo, "LIKE").andExpect(status().isNotFound());
        react(f.fan, processing, "LIKE").andExpect(status().isNotFound());
        unreact(f.fan, privateVideo).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/videos/" + f.video + "/reaction").with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"LIKE\"}"))
                .andExpect(status().isUnauthorized());
        // The owner can see their own private video, so they may react to it.
        react(f.owner, privateVideo, "LIKE").andExpect(status().isOk());

        assertThat(reactionRows(privateVideo)).isEqualTo(1);
        assertThat(reactionRows(processing)).isZero();
    }

    @Test
    void invalidReactionIs400() throws Exception {
        Fixture f = fixture();

        react(f.fan, f.video, "LOVE").andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/videos/" + f.video + "/reaction").session(f.fan).with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void detailsCarryTheCallersOwnReaction() throws Exception {
        Fixture f = fixture();
        react(f.fan, f.video, "LIKE").andExpect(status().isOk());
        String url = "/api/v1/videos/" + f.video;

        mvc.perform(get(url)).andExpect(jsonPath("$.myReaction").isEmpty())
                .andExpect(jsonPath("$.likeCount").value(1));
        mvc.perform(get(url).session(f.fan)).andExpect(jsonPath("$.myReaction").value("LIKE"));
        mvc.perform(get(url).session(f.owner)).andExpect(jsonPath("$.myReaction").isEmpty());
    }

    // The point of locking the parent row before recounting: many writers at
    // once must still leave every counter equal to the rows behind it.
    @Test
    void concurrentSubscribesAndReactionsKeepEveryCounterExact() throws Exception {
        Fixture f = fixture();
        int users = 20;
        List<MockHttpSession> sessions = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            sessions.add(login(tag()));
        }

        ExecutorService pool = Executors.newFixedThreadPool(users);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            MockHttpSession session = sessions.get(i);
            String reaction = i % 2 == 0 ? "LIKE" : "DISLIKE";
            done.add(pool.submit(() -> {
                go.await(); // everyone starts together
                subscribe(session, f.channelHandle).andExpect(status().isOk());
                react(session, f.video, reaction).andExpect(status().isOk());
                return null;
            }));
        }
        go.countDown();
        for (Future<?> future : done) {
            future.get(60, TimeUnit.SECONDS); // rethrows any failed assertion
        }
        pool.shutdown();

        assertThat(subscriptionRows(f.channelId)).isEqualTo(users);
        assertThat(storedSubscriberCount(f.channelId)).isEqualTo(users);
        assertThat(reactionRows(f.video)).isEqualTo(users);
        assertThat(storedCounts(f.video)).containsExactly(users / 2L, users / 2L);
    }

    // ---------------------------------------------------------------- helpers

    // An owner with a channel and a public video, plus a logged-in fan.
    private record Fixture(MockHttpSession owner, MockHttpSession fan, UUID channelId, String channelHandle, UUID video) {
    }

    private Fixture fixture() throws Exception {
        String ownerTag = tag();
        MockHttpSession owner = login(ownerTag);
        UUID channel = jdbc.queryForObject("SELECT channel_id FROM channels WHERE channel_handle = ?", UUID.class,
                handle(ownerTag));
        return new Fixture(owner, login(tag()), channel, handle(ownerTag), seedVideo(channel, "READY", "PUBLIC"));
    }

    private ResultActions subscribe(MockHttpSession session, String handle) throws Exception {
        return mvc.perform(put("/api/v1/channels/" + handle + "/subscription").session(session).with(csrfToken()));
    }

    private ResultActions unsubscribe(MockHttpSession session, String handle) throws Exception {
        return mvc.perform(delete("/api/v1/channels/" + handle + "/subscription").session(session).with(csrfToken()));
    }

    private ResultActions react(MockHttpSession session, UUID video, String reaction) throws Exception {
        return mvc.perform(put("/api/v1/videos/" + video + "/reaction").session(session).with(csrfToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"" + reaction + "\"}"));
    }

    private ResultActions unreact(MockHttpSession session, UUID video) throws Exception {
        return mvc.perform(delete("/api/v1/videos/" + video + "/reaction").session(session).with(csrfToken()));
    }

    private long subscriptionRows(UUID channel) {
        return jdbc.queryForObject("SELECT count(*) FROM subscriptions WHERE channel_id = ?", Long.class, channel);
    }

    private long storedSubscriberCount(UUID channel) {
        return jdbc.queryForObject("SELECT subscriber_count FROM channels WHERE channel_id = ?", Long.class, channel);
    }

    private long reactionRows(UUID video) {
        return jdbc.queryForObject("SELECT count(*) FROM video_reactions WHERE video_id = ?", Long.class, video);
    }

    private List<Long> storedCounts(UUID video) {
        return jdbc.query("SELECT like_count, dislike_count FROM videos WHERE video_id = ?",
                (rs, row) -> List.of(rs.getLong("like_count"), rs.getLong("dislike_count")), video).get(0);
    }

    // The real browser flow: GET for the XSRF-TOKEN cookie, send it back as cookie + header.
    // Not Spring's csrf() helper: it permanently swaps the token repository on the shared filter chain.
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

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String handle(String tag) {
        return "u_" + tag;
    }

    // Registers a user (which also creates their channel) and logs them in.
    private MockHttpSession login(String tag) throws Exception {
        mvc.perform(post("/api/v1/auth/register").with(csrfToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + tag + "@test.dev\",\"password\":\"" + PASSWORD
                                + "\",\"userName\":\"Test\",\"handle\":\"" + handle(tag) + "\"}"))
                .andExpect(status().isCreated());
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(csrfToken())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", tag + "@test.dev").param("password", PASSWORD))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private UUID seedVideo(UUID channel, String status, String visibility) {
        return jdbc.queryForObject(
                "INSERT INTO videos (channel_id, title, status, visibility) VALUES (?, 'v', ?, ?) RETURNING video_id",
                UUID.class, channel, status, visibility);
    }
}
