package com.ladder.transcode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import com.ladder.storage.StorageKeys;
import com.ladder.storage.StorageProperties;
import com.ladder.transcode.FfmpegTranscoder.Rendition;
import com.ladder.video.VideoRepository;
import com.ladder.video.VideoResolutionRepository;

import io.minio.DownloadObjectArgs;
import io.minio.MinioClient;
import io.minio.UploadObjectArgs;
import io.minio.errors.MinioException;
import lombok.extern.slf4j.Slf4j;

/**
 * Consumes one TranscodeJob = one rendition of one video:
 * download source -> ffmpeg -> upload segments -> upsert video_resolutions.
 *
 * Retries and dead-lettering are configured in application.properties
 * (spring.rabbitmq.listener.simple.*): any exception thrown here is retried a
 * bounded number of times, then the message goes to the dead-letter queue.
 */
@Slf4j
@Component
public class TranscodeWorker {
    private final FfmpegTranscoder transcoder;
    private final MinioClient minioClient;
    private final StorageProperties storage;
    private final VideoResolutionRepository resolutions;
    private final VideoRepository videos;
    private final TranscodeCoordinator coordinator;

    public TranscodeWorker(FfmpegTranscoder transcoder, MinioClient minioClient, StorageProperties storage,
                           VideoResolutionRepository resolutions, VideoRepository videos,
                           TranscodeCoordinator coordinator) {
        this.transcoder = transcoder;
        this.minioClient = minioClient;
        this.storage = storage;
        this.resolutions = resolutions;
        this.videos = videos;
        this.coordinator = coordinator;
    }

    @RabbitListener(queues = TranscodeQueueConfig.QUEUE)
    public void handle(TranscodeJob job) {
        Rendition rendition = Rendition.fromLabel(job.resolution())
                .orElseThrow(() -> new IllegalArgumentException("unknown resolution: " + job.resolution()));

        // A fresh directory per job: two jobs never share files, and a retry
        // starts clean instead of finding half-written segments.
        Path workDir = createWorkDir(job);
        try {
            Path source = workDir.resolve("source.mp4");
            Path outputDir = workDir.resolve("out");
            Files.createDirectory(outputDir);

            download(StorageKeys.sourceKey(job.videoId()), source);
            transcoder.transcode(source, outputDir, rendition);

            String prefix = StorageKeys.renditionPrefix(job.videoId(), rendition.label());
            int segmentCount = uploadAll(outputDir, prefix);

            // Last step on purpose: the row only appears once every file is in
            // storage, so a row always means a playable rendition.
            resolutions.upsert(job.videoId(), rendition.label(), prefix + "playlist.m3u8",
                    segmentCount, rendition.videoBitrateKbps());
            log.info("transcoded {} {} ({} segments)", job.videoId(), rendition.label(), segmentCount);

            // After the upsert has committed (it runs in its own transaction), so
            // this job's row is visible to the check.
            coordinator.onRenditionDone(job.videoId());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            // Always clean up: a 4K source plus four renditions is several GB,
            // and a worker that leaks temp dirs fills its disk within a day.
            deleteQuietly(workDir);
        }
    }

    // Reached only after a job used up its retries (see TranscodeQueueConfig).
    @RabbitListener(queues = TranscodeQueueConfig.DEAD_LETTER_QUEUE)
    public void handleDeadLetter(TranscodeJob job) {
        log.error("transcode job dead-lettered, marking video FAILED: {}", job);
        videos.markFailed(job.videoId());
    }

    private void download(String objectKey, Path target) {
        try {
            minioClient.downloadObject(DownloadObjectArgs.builder()
                    .bucket(storage.bucket())
                    .object(objectKey)
                    .filename(target.toString())
                    .build());
        } catch (MinioException e) {
            throw new IllegalStateException("could not download " + objectKey, e);
        }
    }

    /** Uploads every file ffmpeg wrote; returns how many were segments. */
    private int uploadAll(Path outputDir, String prefix) throws IOException {
        List<Path> files;
        try (Stream<Path> listing = Files.list(outputDir)) {
            files = listing.toList();
        }
        int segments = 0;
        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                minioClient.uploadObject(UploadObjectArgs.builder()
                        .bucket(storage.bucket())
                        .object(prefix + name)
                        .filename(file.toString())
                        .contentType(contentType(name))
                        .build());
            } catch (MinioException e) {
                throw new IllegalStateException("could not upload " + prefix + name, e);
            }
            if (name.endsWith(".ts")) {
                segments++;
            }
        }
        return segments;
    }

    // Players (Safari's native HLS especially) check these types; storage would
    // otherwise default to application/octet-stream.
    private static String contentType(String fileName) {
        if (fileName.endsWith(".m3u8")) {
            return "application/vnd.apple.mpegurl";
        }
        if (fileName.endsWith(".ts")) {
            return "video/mp2t";
        }
        return "application/octet-stream";
    }

    private static Path createWorkDir(TranscodeJob job) {
        try {
            return Files.createTempDirectory("transcode-" + job.videoId() + "-" + job.resolution() + "-");
        } catch (IOException e) {
            throw new UncheckedIOException("could not create work dir", e);
        }
    }

    private static void deleteQuietly(Path dir) {
        try {
            FileSystemUtils.deleteRecursively(dir);
        } catch (IOException e) {
            log.warn("could not delete work dir {}", dir, e);
        }
    }
}
