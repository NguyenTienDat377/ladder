package com.ladder.video;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
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
    private final VideoRepository videoRepository;
    private final MinioClient minioClient;
    private final StorageProperties storageProperties;
    private static final int SEGMENT_URL_EXPIRY_HOURS = 6;
    public PlaybackService(VideoRepository videoRepository, MinioClient minioClient, StorageProperties storageProperties) {
        this.videoRepository = videoRepository;
        this.minioClient = minioClient;
        this.storageProperties = storageProperties;
    }

    public String masterPlaylist(UUID id) {
        requirePlayable(id);
        return readObject(StorageKeys.masterPlaylistKey(id));
    }

    private void requirePlayable(UUID id) {
        boolean ready = videoRepository.findById(id)
                .map(video -> "READY".equals(video.getStatus()))
                .orElse(false);
        if (!ready) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private String readObject(String key) {
        try (GetObjectResponse in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(storageProperties.bucket())
                .object(key)
                .build())) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (MinioException | IOException e) {
            throw new IllegalStateException("could not read " + key, e);
        }
    }
    public String renditionPlaylist(UUID id, String resolution) {
        Rendition rendition = Rendition.fromLabel(resolution)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requirePlayable(id);

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
