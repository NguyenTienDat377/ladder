package com.ladder.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.minio.MinioClient;

// Real Postgres and Redis in throwaway containers: the point of these tests is
// the Redis/Postgres hand-off, which a mock of either would hide.
@Testcontainers
@SpringBootTest(properties = {
        // No RabbitMQ in the test: the transcode listeners must not try to connect.
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // The scheduler must not fire mid-test; tests call flush() themselves.
        "ladder.views.flush-interval=PT1H"
})
class ViewCountServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:8").withExposedPorts(6379);

    // The startup runner would otherwise try to reach object storage.
    @MockitoBean
    MinioClient minioClient;

    @Autowired ViewCountService views;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanRedis() {
        Set<String> keys = redis.keys("views:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void sameViewerCountedOnceWithinWindow() {
        UUID video = seedVideo("READY");

        views.recordView(video, "1.1.1.1", null);
        long second = views.recordView(video, "1.1.1.1", null);

        assertThat(pending(video)).isEqualTo(1);
        assertThat(second).isEqualTo(1);
    }

    @Test
    void differentViewersAreBothCounted() {
        UUID video = seedVideo("READY");

        views.recordView(video, "1.1.1.1", null);
        long second = views.recordView(video, "2.2.2.2", null);

        assertThat(pending(video)).isEqualTo(2);
        assertThat(second).isEqualTo(2);
    }

    @Test
    void videoThatIsNotReadyReturns404AndWritesNothing() {
        UUID video = seedVideo("PROCESSING");

        assertThatThrownBy(() -> views.recordView(video, "1.1.1.1", null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));

        assertThat(redis.keys("views:*")).isEmpty();
    }

    @Test
    void flushMovesPendingViewsIntoTheDatabase() {
        UUID video = seedVideo("READY");
        views.recordView(video, "1.1.1.1", null);
        views.recordView(video, "2.2.2.2", null);
        views.recordView(video, "3.3.3.3", null);

        views.flush();

        assertThat(storedViews(video)).isEqualTo(3);
        assertThat(redis.hasKey(ViewCountService.PENDING)).isFalse();
        assertThat(redis.hasKey(ViewCountService.FLUSHING)).isFalse();
        // The displayed number must not change just because views moved stores.
        assertThat(views.recordView(video, "1.1.1.1", null)).isEqualTo(3);
    }

    @Test
    void flushAddsToWhatTheDatabaseAlreadyHolds() {
        UUID video = seedVideo("READY");
        jdbc.update("UPDATE videos SET view_count = 10 WHERE video_id = ?", video);
        views.recordView(video, "1.1.1.1", null);

        views.flush();

        assertThat(storedViews(video)).isEqualTo(11);
    }

    @Test
    void flushFinishesABatchLeftBehindByACrashedFlush() {
        UUID video = seedVideo("READY");
        // What a flush that died before its DEL leaves behind.
        redis.opsForHash().put(ViewCountService.FLUSHING, video.toString(), "5");

        views.flush();

        assertThat(storedViews(video)).isEqualTo(5);
        assertThat(redis.hasKey(ViewCountService.FLUSHING)).isFalse();
    }

    @Test
    void flushWithNothingPendingDoesNothing() {
        views.flush(); // must not throw: RENAME on a missing key is an error
    }

    private long pending(UUID video) {
        Object value = redis.opsForHash().get(ViewCountService.PENDING, video.toString());
        return value == null ? 0 : Long.parseLong(value.toString());
    }

    private long storedViews(UUID video) {
        return jdbc.queryForObject("SELECT view_count FROM videos WHERE video_id = ?", Long.class, video);
    }

    // users -> channels -> videos: each table needs its parent row. PUBLIC, because the
    // column defaults to PRIVATE and an anonymous viewer can't count views on a private video.
    private UUID seedVideo(String status) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        UUID user = jdbc.queryForObject(
                "INSERT INTO users (email, user_name, user_handle) VALUES (?, 'T', ?) RETURNING user_id",
                UUID.class, tag + "@test.dev", "u" + tag);
        UUID channel = jdbc.queryForObject(
                "INSERT INTO channels (user_id, channel_name, channel_handle) VALUES (?, 'T', ?) RETURNING channel_id",
                UUID.class, user, "c" + tag);
        return jdbc.queryForObject(
                "INSERT INTO videos (channel_id, title, status, visibility) VALUES (?, 'v', ?, 'PUBLIC') RETURNING video_id",
                UUID.class, channel, status);
    }
}
