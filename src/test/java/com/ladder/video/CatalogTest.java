package com.ladder.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
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

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import jakarta.servlet.http.Cookie;

// The read side (feed, details, channel pages) and the metadata edit, against
// a real database and the real security chain.
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "ladder.views.flush-interval=PT1H"
})
class CatalogTest {

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
    @Autowired StringRedisTemplate redis;

    private static final String PASSWORD = "correct horse battery";

    // The feed is global, so every test starts from an empty videos table.
    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM videos");
        Set<String> keys = redis.keys("views:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void feedIsNewestFirstAndPagesByKeyset() throws Exception {
        UUID channel = newChannel();
        List<String> expected = Stream.of(1, 2, 3)
                .map(i -> seedVideo(channel, "READY", "PUBLIC", "v" + i).toString())
                // UUIDv7 ids sort by creation time; Postgres compares them as
                // unsigned bytes, which is the same order as their lowercase hex text.
                .sorted(Comparator.reverseOrder())
                .toList();

        mvc.perform(get("/api/v1/videos?limit=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].videoId").value(expected.get(0)))
                .andExpect(jsonPath("$.items[1].videoId").value(expected.get(1)))
                .andExpect(jsonPath("$.nextBefore").value(expected.get(1)));

        mvc.perform(get("/api/v1/videos?limit=2&before=" + expected.get(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].videoId").value(expected.get(2)))
                .andExpect(jsonPath("$.nextBefore").isEmpty());
    }

    @Test
    void feedHasOnlyPublicReadyVideosAndNoThumbnailYet() throws Exception {
        UUID channel = newChannel();
        UUID shown = seedVideo(channel, "READY", "PUBLIC", "shown");
        seedVideo(channel, "READY", "PRIVATE", "private");
        seedVideo(channel, "READY", "UNLISTED", "unlisted");
        seedVideo(channel, "PROCESSING", "PUBLIC", "processing");
        seedVideo(channel, "FAILED", "PUBLIC", "failed");

        mvc.perform(get("/api/v1/videos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].videoId").value(shown.toString()))
                .andExpect(jsonPath("$.items[0].thumbnailUrl").isEmpty());
    }

    @Test
    void feedQueryIsShapedSoThePartialIndexCanServeIt() {
        // On a tiny table Postgres rightly prefers a sequential scan, so switch
        // that off and ask whether the index is USABLE for this query shape.
        // (If someone rewrites the WHERE clause so it no longer implies the
        // index's predicate, this stops finding the index.)
        String plan = jdbc.execute((java.sql.Connection connection) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                try (var result = statement.executeQuery("""
                        EXPLAIN SELECT v.video_id FROM videos v
                        WHERE v.status = 'READY' AND v.visibility = 'PUBLIC'
                          AND v.video_id < 'ffffffff-ffff-ffff-ffff-ffffffffffff'
                        ORDER BY v.video_id DESC LIMIT 25""")) {
                    StringBuilder text = new StringBuilder();
                    while (result.next()) {
                        text.append(result.getString(1)).append('\n');
                    }
                    return text.toString();
                } finally {
                    statement.execute("RESET enable_seqscan");
                }
            }
        });
        assertThat(plan).contains("idx_videos_feed");
    }

    @Test
    void feedLimitIsClampedAndABadCursorIs400() throws Exception {
        UUID channel = newChannel();
        seedVideo(channel, "READY", "PUBLIC", "one");

        mvc.perform(get("/api/v1/videos?limit=0")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/v1/videos?limit=100000")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/videos?before=not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void channelPageShowsPublicToEveryoneAndEverythingToTheOwner() throws Exception {
        String owner = tag();
        String stranger = tag();
        register(owner).andExpect(status().isCreated());
        register(stranger).andExpect(status().isCreated());
        UUID channel = channelOf(owner);
        seedVideo(channel, "READY", "PUBLIC", "public");
        seedVideo(channel, "READY", "PRIVATE", "private");
        seedVideo(channel, "READY", "UNLISTED", "unlisted");
        seedVideo(channel, "PROCESSING", "PRIVATE", "processing");
        String url = "/api/v1/channels/" + handle(owner);

        // Anonymous and another logged-in user: the public video only.
        for (MockHttpSession viewer : new MockHttpSession[] {null, loginSession(stranger)}) {
            var request = get(url);
            if (viewer != null) {
                request.session(viewer);
            }
            mvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.channelHandle").value(handle(owner)))
                    .andExpect(jsonPath("$.canManage").value(false))
                    .andExpect(jsonPath("$.publicVideoCount").value(1))
                    .andExpect(jsonPath("$.videos.items.length()").value(1))
                    .andExpect(jsonPath("$.videos.items[0].title").value("public"));
        }

        // The owner: all four, in every state, and the count still counts only the public one.
        mvc.perform(get(url).session(loginSession(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canManage").value(true))
                .andExpect(jsonPath("$.publicVideoCount").value(1))
                .andExpect(jsonPath("$.videos.items.length()").value(4));

        mvc.perform(get("/api/v1/channels/nobody_" + tag())).andExpect(status().isNotFound());
    }

    @Test
    void privateVideoDetailsAre404ForAnonymousAndStrangersButOkForTheOwner() throws Exception {
        String owner = tag();
        String stranger = tag();
        register(owner).andExpect(status().isCreated());
        register(stranger).andExpect(status().isCreated());
        UUID video = seedVideo(channelOf(owner), "READY", "PRIVATE", "secret");
        String url = "/api/v1/videos/" + video;

        mvc.perform(get(url)).andExpect(status().isNotFound());
        mvc.perform(get(url).session(loginSession(stranger))).andExpect(status().isNotFound());
        mvc.perform(get(url).session(loginSession(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("secret"))
                .andExpect(jsonPath("$.channel.channelHandle").value(handle(owner)));
    }

    @Test
    void detailsOfAPublicVideoCarryChannelAndCounts() throws Exception {
        String owner = tag();
        register(owner).andExpect(status().isCreated());
        UUID video = seedVideo(channelOf(owner), "READY", "PUBLIC", "hello");
        jdbc.update("UPDATE videos SET description = 'about it', duration_secs = 31, like_count = 4, dislike_count = 1 "
                + "WHERE video_id = ?", video);
        jdbc.update("UPDATE channels SET subscriber_count = 9 WHERE channel_handle = ?", handle(owner));

        mvc.perform(get("/api/v1/videos/" + video))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("about it"))
                .andExpect(jsonPath("$.durationSecs").value(31))
                .andExpect(jsonPath("$.likeCount").value(4))
                .andExpect(jsonPath("$.dislikeCount").value(1))
                .andExpect(jsonPath("$.channel.channelName").value("Test"))
                .andExpect(jsonPath("$.channel.subscriberCount").value(9));
    }

    @Test
    void detailsViewCountIncludesViewsStillWaitingInRedis() throws Exception {
        UUID channel = newChannel();
        UUID video = seedVideo(channel, "READY", "PUBLIC", "popular");
        jdbc.update("UPDATE videos SET view_count = 5 WHERE video_id = ?", video);
        redis.opsForHash().increment(ViewCountService.PENDING, video.toString(), 3);

        mvc.perform(get("/api/v1/videos/" + video))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewCount").value(8));
    }

    @Test
    void patchNeedsLoginAndPermissionAndAnExistingVideo() throws Exception {
        String owner = tag();
        String stranger = tag();
        register(owner).andExpect(status().isCreated());
        register(stranger).andExpect(status().isCreated());
        UUID video = seedVideo(channelOf(owner), "READY", "PRIVATE", "before");
        String body = "{\"title\":\"after\"}";

        patchJson(null, video, body).andExpect(status().isUnauthorized());
        patchJson(loginSession(stranger), video, body).andExpect(status().isForbidden());
        patchJson(loginSession(owner), UUID.randomUUID(), body).andExpect(status().isNotFound());
        patchJson(loginSession(owner), video, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("after"));

        assertThat(jdbc.queryForObject("SELECT title FROM videos WHERE video_id = ?", String.class, video))
                .isEqualTo("after");
    }

    @Test
    void patchChangesOnlyWhatWasSent() throws Exception {
        String owner = tag();
        register(owner).andExpect(status().isCreated());
        UUID video = seedVideo(channelOf(owner), "READY", "PRIVATE", "keep me");
        jdbc.update("UPDATE videos SET description = 'old text' WHERE video_id = ?", video);
        MockHttpSession session = loginSession(owner);

        patchJson(session, video, "{\"description\":\"new text\"}").andExpect(status().isOk());

        assertThat(jdbc.queryForMap("SELECT title, description, visibility, status FROM videos WHERE video_id = ?", video))
                .containsEntry("title", "keep me")
                .containsEntry("description", "new text")
                .containsEntry("visibility", "PRIVATE")
                .containsEntry("status", "READY"); // the edit never touches status

        patchJson(session, video, "{\"description\":\"\"}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT description FROM videos WHERE video_id = ?", String.class, video))
                .isEmpty();
    }

    @Test
    void patchingVisibilityChangesWhatTheFeedShows() throws Exception {
        String owner = tag();
        register(owner).andExpect(status().isCreated());
        UUID video = seedVideo(channelOf(owner), "READY", "PRIVATE", "toggle");
        MockHttpSession session = loginSession(owner);

        mvc.perform(get("/api/v1/videos")).andExpect(jsonPath("$.items.length()").value(0));

        patchJson(session, video, "{\"visibility\":\"PUBLIC\"}").andExpect(status().isOk());
        mvc.perform(get("/api/v1/videos"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].videoId").value(video.toString()));
        // And now anyone can open it.
        mvc.perform(get("/api/v1/videos/" + video)).andExpect(status().isOk());

        patchJson(session, video, "{\"visibility\":\"UNLISTED\"}").andExpect(status().isOk());
        mvc.perform(get("/api/v1/videos")).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/videos/" + video)).andExpect(status().isOk()); // unlisted: link works

        patchJson(session, video, "{\"visibility\":\"PRIVATE\"}").andExpect(status().isOk());
        mvc.perform(get("/api/v1/videos/" + video)).andExpect(status().isNotFound());
    }

    @Test
    void patchRejectsInvalidInput() throws Exception {
        String owner = tag();
        register(owner).andExpect(status().isCreated());
        UUID video = seedVideo(channelOf(owner), "READY", "PRIVATE", "valid");
        MockHttpSession session = loginSession(owner);

        patchJson(session, video, "{\"visibility\":\"SECRET\"}").andExpect(status().isBadRequest());
        patchJson(session, video, "{\"title\":\"   \"}").andExpect(status().isBadRequest());
        patchJson(session, video, "{\"title\":\"" + "x".repeat(201) + "\"}").andExpect(status().isBadRequest());
        patchJson(session, video, "{\"description\":\"" + "x".repeat(5001) + "\"}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT title FROM videos WHERE video_id = ?", String.class, video))
                .isEqualTo("valid");
    }

    @Test
    void createVideoStoresTheDescription() throws Exception {
        String owner = tag();
        register(owner).andExpect(status().isCreated());
        String channel = channelOf(owner).toString();

        mvc.perform(post("/api/v1/videos").session(loginSession(owner)).with(csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelId\":\"" + channel + "\",\"title\":\"t\",\"description\":\"about\"}"))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT description FROM videos WHERE title = 't' AND channel_id = ?::uuid",
                String.class, channel)).isEqualTo("about");
    }

    @Test
    void thumbnailIsServedWithAnEtagAndAPublicCacheAndAnswers304ToARepeatVisit() throws Exception {
        UUID video = seedVideo(newChannel(), "READY", "PUBLIC", "pic");
        byte[] jpeg = withThumbnail(video);

        String etag = mvc.perform(get("/api/v1/videos/" + video + "/thumbnail"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(jpeg))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("max-age=3600"),
                        org.hamcrest.Matchers.containsString("public"))))
                .andReturn().getResponse().getHeader("ETag");
        assertThat(etag).isNotBlank();

        mvc.perform(get("/api/v1/videos/" + video + "/thumbnail").header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void unlistedThumbnailIsCacheableToo() throws Exception {
        UUID video = seedVideo(newChannel(), "READY", "UNLISTED", "link only");
        withThumbnail(video);

        mvc.perform(get("/api/v1/videos/" + video + "/thumbnail"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")));
    }

    @Test
    void aPrivateThumbnailIsHiddenFromEveryoneButItsOwnerAndNeverSharedByCaches() throws Exception {
        String owner = tag();
        String stranger = tag();
        register(owner).andExpect(status().isCreated());
        register(stranger).andExpect(status().isCreated());
        UUID video = seedVideo(channelOf(owner), "READY", "PRIVATE", "secret");
        withThumbnail(video);
        String url = "/api/v1/videos/" + video + "/thumbnail";

        mvc.perform(get(url)).andExpect(status().isNotFound());
        mvc.perform(get(url).session(loginSession(stranger))).andExpect(status().isNotFound());
        mvc.perform(get(url).session(loginSession(owner)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("private"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("public")))));
    }

    @Test
    void noThumbnailOrNotReadyIs404() throws Exception {
        UUID channel = newChannel();
        UUID without = seedVideo(channel, "READY", "PUBLIC", "no picture yet");
        UUID processing = seedVideo(channel, "PROCESSING", "PUBLIC", "still processing");
        withThumbnail(processing);

        mvc.perform(get("/api/v1/videos/" + without + "/thumbnail")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/videos/" + processing + "/thumbnail")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/videos/" + UUID.randomUUID() + "/thumbnail")).andExpect(status().isNotFound());
    }

    @Test
    void feedChannelPageAndDetailsLinkToTheThumbnailOnlyWhenThereIsOne() throws Exception {
        String owner = tag();
        register(owner).andExpect(status().isCreated());
        UUID channel = channelOf(owner);
        UUID pictured = seedVideo(channel, "READY", "PUBLIC", "pictured");
        UUID plain = seedVideo(channel, "READY", "PUBLIC", "plain");
        UUID processing = seedVideo(channel, "PROCESSING", "PRIVATE", "processing");
        withThumbnail(pictured);
        withThumbnail(processing);
        String link = "/api/v1/videos/" + pictured + "/thumbnail";

        mvc.perform(get("/api/v1/videos"))
                .andExpect(jsonPath("$.items[?(@.videoId=='" + pictured + "')].thumbnailUrl").value(link))
                .andExpect(jsonPath("$.items[?(@.videoId=='" + plain + "')].thumbnailUrl").value((Object) null));
        mvc.perform(get("/api/v1/videos/" + pictured)).andExpect(jsonPath("$.thumbnailUrl").value(link));
        mvc.perform(get("/api/v1/videos/" + plain)).andExpect(jsonPath("$.thumbnailUrl").isEmpty());
        // The owner's list includes the unfinished one, but it must not link to a 404.
        mvc.perform(get("/api/v1/channels/" + handle(owner)).session(loginSession(owner)))
                .andExpect(jsonPath("$.videos.items[?(@.videoId=='" + processing + "')].thumbnailUrl").value((Object) null));
    }

    @Test
    void searchFindsByTitleDescriptionAndFuzzyTypoAndRanksTitleFirst() throws Exception {
        UUID channel = newChannel();
        UUID descOnly = seedVideo(channel, "READY", "PUBLIC", "Morning walk");
        jdbc.update("UPDATE videos SET description = 'Talking about adaptive bitrate ladders' WHERE video_id = ?", descOnly);
        UUID typoTitle = seedVideo(channel, "READY", "PUBLIC", "Adaptive bitrat ladder");
        UUID exactTitle = seedVideo(channel, "READY", "PUBLIC", "Adaptive bitrate streaming");
        seedVideo(channel, "READY", "PUBLIC", "Cooking pasta");

        // Exact query "bitrate": exact title match (weight A + trigram 1.0) >
        // fuzzy title match ("bitrat") > description-only match (weight B).
        mvc.perform(get("/api/v1/videos/search").param("q", "bitrate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].videoId").value(exactTitle.toString()))
                .andExpect(jsonPath("$.items[1].videoId").value(typoTitle.toString()))
                .andExpect(jsonPath("$.items[2].videoId").value(descOnly.toString()))
                .andExpect(jsonPath("$.nextOffset").isEmpty());

        // Typo query "bitrat": tsvector won't match "bitrate", so pg_trgm carries it.
        mvc.perform(get("/api/v1/videos/search").param("q", "bitrat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.videoId=='" + exactTitle + "')]").exists());
    }

    @Test
    void searchHidesNonPublicAndUnfinishedVideos() throws Exception {
        UUID channel = newChannel();
        UUID shown = seedVideo(channel, "READY", "PUBLIC", "transcode pipeline");
        seedVideo(channel, "READY", "PRIVATE", "transcode pipeline");
        seedVideo(channel, "READY", "UNLISTED", "transcode pipeline");
        seedVideo(channel, "PROCESSING", "PUBLIC", "transcode pipeline");
        seedVideo(channel, "FAILED", "PUBLIC", "transcode pipeline");

        mvc.perform(get("/api/v1/videos/search").param("q", "transcode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].videoId").value(shown.toString()));
    }

    @Test
    void searchTieBreaksByNewestFirstAndPagesWithOffset() throws Exception {
        UUID channel = newChannel();
        List<String> expected = Stream.of(1, 2, 3)
                .map(i -> seedVideo(channel, "READY", "PUBLIC", "keyframes guide").toString())
                .sorted(Comparator.reverseOrder())
                .toList();

        mvc.perform(get("/api/v1/videos/search").param("q", "keyframes").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].videoId").value(expected.get(0)))
                .andExpect(jsonPath("$.items[1].videoId").value(expected.get(1)))
                .andExpect(jsonPath("$.nextOffset").value(2));

        mvc.perform(get("/api/v1/videos/search").param("q", "keyframes").param("limit", "2").param("offset", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].videoId").value(expected.get(2)))
                .andExpect(jsonPath("$.nextOffset").isEmpty());
    }

    @Test
    void searchHandlesBlankSpecialSyntaxAndRejectsBadParameters() throws Exception {
        UUID channel = newChannel();
        seedVideo(channel, "READY", "PUBLIC", "hello world");

        mvc.perform(get("/api/v1/videos/search").param("q", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextOffset").isEmpty());

        // Raw user punctuation that would crash to_tsquery() is harmless in websearch_to_tsquery().
        mvc.perform(get("/api/v1/videos/search").param("q", "\"unclosed & ( ) ! :"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/videos/search").param("q", "hello").param("offset", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/videos/search").param("q", "hello").param("offset", "1001"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/videos/search").param("q", "x".repeat(201)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchQueryIsShapedSoBothGinIndexesCanServeIt() {
        // Every branch of an OR must be indexable for Postgres to build a
        // BitmapOr plan; if any branch used a plain function call instead of an
        // index operator, Postgres would have no choice but a sequential scan.
        String plan = jdbc.execute((java.sql.Connection connection) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                try (var result = statement.executeQuery("""
                        EXPLAIN SELECT v.video_id FROM videos v,
                             websearch_to_tsquery('simple', 'ladder') AS tsq
                        WHERE v.search_vector @@ tsq OR v.title % 'ladder' OR v.title %> 'ladder'""")) {
                    StringBuilder text = new StringBuilder();
                    while (result.next()) {
                        text.append(result.getString(1)).append('\n');
                    }
                    return text.toString();
                } finally {
                    statement.execute("RESET enable_seqscan");
                }
            }
        });
        assertThat(plan).contains("videos_search_idx").contains("videos_title_trgm_idx");
    }

    // Marks the video as having a thumbnail and makes the mocked storage return its bytes.
    private byte[] withThumbnail(UUID video) throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};
        jdbc.update("UPDATE videos SET thumbnail_path = ? WHERE video_id = ?", "videos/" + video + "/thumbnail.jpg", video);
        GetObjectResponse stored = mock(GetObjectResponse.class);
        when(stored.readAllBytes()).thenReturn(jpeg);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(stored);
        return jpeg;
    }

    // ---------------------------------------------------------------- helpers

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

    private ResultActions patchJson(MockHttpSession session, UUID video, String body) throws Exception {
        var request = patch("/api/v1/videos/" + video).with(csrfToken()).contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (session != null) {
            request.session(session);
        }
        return mvc.perform(request);
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private ResultActions register(String tag) throws Exception {
        return mvc.perform(post("/api/v1/auth/register").with(csrfToken()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email(tag) + "\",\"password\":\"" + PASSWORD
                        + "\",\"userName\":\"Test\",\"handle\":\"" + handle(tag) + "\"}"));
    }

    private static String email(String tag) {
        return tag + "@test.dev";
    }

    private static String handle(String tag) {
        return "u_" + tag;
    }

    private MockHttpSession loginSession(String tag) throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/api/v1/auth/login").with(csrfToken())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", email(tag)).param("password", PASSWORD))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private UUID channelOf(String tag) {
        return jdbc.queryForObject("SELECT channel_id FROM channels WHERE channel_handle = ?", UUID.class, handle(tag));
    }

    // A channel with its own throwaway owner, for tests that don't log anyone in.
    private UUID newChannel() {
        String tag = tag();
        UUID user = jdbc.queryForObject(
                "INSERT INTO users (email, user_name, user_handle) VALUES (?, 'T', ?) RETURNING user_id",
                UUID.class, email(tag), handle(tag));
        return jdbc.queryForObject(
                "INSERT INTO channels (user_id, channel_name, channel_handle) VALUES (?, 'Test', ?) RETURNING channel_id",
                UUID.class, user, "c" + tag);
    }

    private UUID seedVideo(UUID channel, String status, String visibility, String title) {
        return jdbc.queryForObject(
                "INSERT INTO videos (channel_id, title, status, visibility) VALUES (?, ?, ?, ?) RETURNING video_id",
                UUID.class, channel, title, status, visibility);
    }
}
