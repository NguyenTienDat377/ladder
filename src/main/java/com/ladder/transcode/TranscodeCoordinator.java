package com.ladder.transcode;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.ladder.storage.StorageKeys;
import com.ladder.storage.StorageProperties;
import com.ladder.transcode.FfmpegTranscoder.Rendition;
import com.ladder.video.VideoRepository;
import com.ladder.video.VideoResolution;
import com.ladder.video.VideoResolutionRepository;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.MinioException;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns "four renditions exist" into "the video is playable": writes the master
 * playlist, then flips the video to READY.
 *
 * Runs after every rendition, from whichever worker just finished. No locks:
 * each worker checks only after its own row is committed, so the last one to
 * commit always sees the full set. Two workers may both see it and both write
 * the master, which is harmless because the content is identical; markReady's
 * status condition then lets exactly one of them flip the status.
 */
@Slf4j
@Component
public class TranscodeCoordinator {
    private static final String PLAYLIST_CONTENT_TYPE = "application/vnd.apple.mpegurl";

    private final VideoResolutionRepository resolutions;
    private final VideoRepository videos;
    private final MinioClient minioClient;
    private final StorageProperties storage;

    public TranscodeCoordinator(VideoResolutionRepository resolutions, VideoRepository videos,
                                MinioClient minioClient, StorageProperties storage) {
        this.resolutions = resolutions;
        this.videos = videos;
        this.minioClient = minioClient;
        this.storage = storage;
    }

    public void onRenditionDone(UUID videoId) {
        Set<String> done = resolutions.findByVideoId(videoId).stream()
                .map(VideoResolution::getResolution)
                .collect(Collectors.toSet());
        boolean allDone = Rendition.LADDER.stream().allMatch(r -> done.contains(r.label()));
        if (!allDone) {
            return;
        }

        // Master first, status second: READY must never be visible before the
        // file the player needs exists.
        writeMasterPlaylist(videoId);
        if (videos.markReady(videoId) == 1) {
            log.info("video {} is READY", videoId);
        }
    }

    private void writeMasterPlaylist(UUID videoId) {
        byte[] content = masterPlaylist().getBytes(StandardCharsets.UTF_8);
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(storage.bucket())
                    .object(StorageKeys.masterPlaylistKey(videoId))
                    .data(content, content.length)
                    .contentType(PLAYLIST_CONTENT_TYPE)
                    .build());
        } catch (MinioException e) {
            throw new IllegalStateException("could not write master playlist for video " + videoId, e);
        }
    }

    /**
     * The same text for every video: entries are relative to the master's own
     * location, and the ladder is fixed. Lowest rung first, so a player that
     * just takes the first entry starts at the safest quality.
     */
    static String masterPlaylist() {
        StringBuilder m3u8 = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n");
        for (Rendition r : Rendition.LADDER) {
            // BANDWIDTH is the PEAK rate the player must sustain, in bits/s:
            // the encoder's maxrate (target x 1.07, as in FfmpegTranscoder) plus audio.
            // ponytail: RESOLUTION and CODECS are omitted. Both are optional, and
            // writing the nominal 16:9 width would be wrong for 4:3 or vertical
            // sources (scale=-2 keeps the source's aspect). Add them once the
            // worker probes the real output dimensions.
            long bandwidth = ((long) (r.videoBitrateKbps() * 1.07) + r.audioBitrateKbps()) * 1000;
            m3u8.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bandwidth).append('\n')
                .append(r.label()).append("/playlist.m3u8\n");
        }
        return m3u8.toString();
    }
}
