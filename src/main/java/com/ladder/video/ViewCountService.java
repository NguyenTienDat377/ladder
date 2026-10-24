package com.ladder.video;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import lombok.extern.slf4j.Slf4j;

/**
 * View counts, as designed: events go to Redis, a periodic job adds them to
 * Postgres. A view never writes a Postgres row by itself.
 *
 * Redis layout:
 *   views:pending            hash  videoId -> views since the last flush
 *   views:flushing           hash  the batch a flush is writing right now
 *   views:seen:{id}:{viewer} "1" with a 30 minute TTL = "already counted"
 */
@Slf4j
@Service
public class ViewCountService {
    static final String PENDING = "views:pending";
    static final String FLUSHING = "views:flushing";
    private static final String SEEN = "views:seen:";
    private static final Duration DEDUPE_WINDOW = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final VideoRepository videos;
    private final TransactionTemplate transaction;

    public ViewCountService(StringRedisTemplate redis, VideoRepository videos, TransactionTemplate transaction) {
        this.redis = redis;
        this.videos = videos;
        this.transaction = transaction;
    }

    /** Counts one view unless this viewer was already counted in the last 30 minutes. */
    public long recordView(UUID videoId, String viewer) {
        // Same rule as playback: a video nobody can watch yet has no views.
        Video video = videos.findById(videoId)
                .filter(v -> "READY".equals(v.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "video not found"));

        // setIfAbsent is one atomic SET NX EX: two simultaneous requests from
        // the same viewer can't both get "true", so they can't both count.
        Boolean first = redis.opsForValue().setIfAbsent(SEEN + videoId + ":" + viewer, "1", DEDUPE_WINDOW);
        if (Boolean.TRUE.equals(first)) {
            redis.opsForHash().increment(PENDING, videoId.toString(), 1);
        }
        return displayedCount(video);
    }

    /** Stored count plus every view still waiting in Redis. */
    long displayedCount(Video video) {
        String id = video.getVideoId().toString();
        // Both hashes: while a flush is running, its batch sits in "flushing"
        // and isn't in the database yet. Skipping it would make the number dip.
        return video.getViewCount() + pendingIn(PENDING, id) + pendingIn(FLUSHING, id);
    }

    private long pendingIn(String hash, String field) {
        Object value = redis.opsForHash().get(hash, field);
        return value == null ? 0 : Long.parseLong(value.toString());
    }

    // fixedDelay, not fixedRate: the next flush starts only after this one has
    // finished, so two flushes never overlap in one instance.
    // ponytail: assumes ONE API instance. A second instance could mistake the
    // first one's in-flight "flushing" for crash leftovers and write it twice;
    // upgrade is a per-run key name. A crash between the commit and the DEL
    // also double-counts that one batch, acceptable for a display counter.
    @Scheduled(fixedDelayString = "${ladder.views.flush-interval:PT1M}",
               initialDelayString = "${ladder.views.flush-interval:PT1M}")
    void flush() {
        // A "flushing" key that is still here means the last flush died before
        // finishing: write that batch first. Otherwise RENAME is the atomic
        // hand-off: every view lands either in the old hash (flushed now) or in
        // a fresh "pending" (next time), never in neither.
        if (!Boolean.TRUE.equals(redis.hasKey(FLUSHING))) {
            if (!Boolean.TRUE.equals(redis.hasKey(PENDING))) {
                return; // nothing to do; RENAME on a missing key is an error
            }
            redis.rename(PENDING, FLUSHING);
        }

        Map<Object, Object> batch = redis.opsForHash().entries(FLUSHING);
        // One transaction: either every video's views are stored or none are,
        // so a failure halfway can't leave a batch half-written and then
        // re-written in full on the retry.
        transaction.executeWithoutResult(status ->
                batch.forEach((id, views) ->
                        videos.addViews(UUID.fromString(id.toString()), Long.parseLong(views.toString()))));

        // Only after the commit: if we die before this line, the leftover
        // check above retries the batch on the next run.
        redis.delete(FLUSHING);
        log.info("flushed views for {} videos", batch.size());
    }
}
