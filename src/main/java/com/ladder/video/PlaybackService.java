package com.ladder.video;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.ladder.storage.StorageKeys;
import com.ladder.storage.StorageProperties;
import com.ladder.transcode.FfmpegTranscoder.Rendition;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.errors.MinioException;
import io.minio.Http;

@Service 
public class PlaybackService {
    private final VideoAccess videoAccess;
    private final MinioClient minioClient;
    private final StorageProperties storageProperties;
    private static final int SEGMENT_URL_EXPIRY_HOURS = 6;
    public PlaybackService(VideoAccess videoAccess, MinioClient minioClient, StorageProperties storageProperties) {
        this.videoAccess = videoAccess;
        this.minioClient = minioClient;
        this.storageProperties = storageProperties;
    }

    /** @param userId the logged-in user, or null for an anonymous visitor */
    public String masterPlaylist(UUID id, UUID userId) {
        videoAccess.requireWatchable(id, userId);
        return readObject(StorageKeys.masterPlaylistKey(id));
    }

    /** The thumbnail JPEG and whether caches may share it between users. */
    public record Thumbnail(byte[] bytes, boolean shareable) {
    }

    /** @param userId the logged-in user, or null for an anonymous visitor */
    public Thumbnail thumbnail(UUID id, UUID userId) {
        Video video = videoAccess.requireWatchable(id, userId);
        if (video.getThumbnailPath() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no thumbnail");
        }
        // A shared cache (browser, later a CDN) may keep PUBLIC and UNLISTED
        // thumbnails for anyone, but must not serve a PRIVATE one to someone
        // who isn't allowed to see it.
        return new Thumbnail(readBytes(video.getThumbnailPath()), !"PRIVATE".equals(video.getVisibility()));
    }

    private String readObject(String key) {
        return new String(readBytes(key), StandardCharsets.UTF_8);
    }

    private byte[] readBytes(String key) {
        try (GetObjectResponse in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(storageProperties.bucket())
                .object(key)
                .build())) {
            return in.readAllBytes();
        } catch (MinioException | IOException e) {
            throw new IllegalStateException("could not read " + key, e);
        }
    }

    public String renditionPlaylist(UUID id, String resolution, UUID userId) {
        Rendition rendition = Rendition.fromLabel(resolution)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        videoAccess.requireWatchable(id, userId);

        String prefix = StorageKeys.renditionPrefix(id, rendition.label());
        return readObject(prefix + "playlist.m3u8").lines()
            .map(line -> line.isBlank() || line.startsWith("#") ? line : presignGet(prefix + line))
            .collect(Collectors.joining("\n", "", "\n"));
        
    }
    private String presignGet(String key) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Http.Method.GET)
                    .bucket(storageProperties.bucket())
                    .object(key)
                    .expiry(SEGMENT_URL_EXPIRY_HOURS, TimeUnit.HOURS)
                    .build());
        } catch (MinioException e) {
            throw new IllegalStateException("could not sign " + key, e);
        }
    }
}
