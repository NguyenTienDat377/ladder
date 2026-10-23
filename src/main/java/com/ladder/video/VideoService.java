package com.ladder.video;

import java.util.concurrent.TimeUnit;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.ladder.storage.StorageKeys;
import com.ladder.storage.StorageProperties;
import com.ladder.video.dto.VideoCreateRequest;
import com.ladder.video.dto.VideoCreateResponse;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;

@Service
public class VideoService {
    private static final int UPLOAD_URL_EXPIRY_MINUTES = 15;

    private final VideoRepository videoRepository;
    private final MinioClient minioClient;
    private final StorageProperties storage;

    public VideoService(VideoRepository videoRepository, MinioClient minioClient, StorageProperties storage) {
        this.videoRepository = videoRepository;
        this.minioClient = minioClient;
        this.storage = storage;
    }

    @Transactional
    public VideoCreateResponse createVideo(VideoCreateRequest request) {
        Video video = new Video();
        video.setChannelId(request.channelId());
        video.setTitle(request.title());
        // Set explicitly: Hibernate writes every mapped column, so a null here
        // would override the DB default and fail NOT NULL.
        video.setStatus("UPLOADING");
        video.setVisibility("PRIVATE");
        videoRepository.save(video);

        String objectKey = StorageKeys.sourceKey(video.getVideoId());

        try {
            String uploadUrl = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Http.Method.PUT)
                    .bucket(storage.bucket())
                    .object(objectKey)
                    .expiry(UPLOAD_URL_EXPIRY_MINUTES, TimeUnit.MINUTES)
                    .build());
            return new VideoCreateResponse(video.getVideoId(), uploadUrl);
        } catch (MinioException e) {
            // Rethrown unchecked on purpose: @Transactional only rolls back on
            // RuntimeException, so this undoes the row instead of leaving an orphan.
            throw new IllegalStateException("could not sign upload URL for video " + video.getVideoId(), e);
        }
    }
    
    // Not @Transactional: statObject is a network call, and holding a DB
    // connection open while waiting on storage wastes the pool. The only write
    // is markProcessing, which runs in its own short transaction.
    public void completeUpload(UUID videoId) {
        if (!videoRepository.existsById(videoId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "video not found");
        }
        if (!uploadExists(videoId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no upload found for this video");
        }
        if (videoRepository.markProcessing(videoId) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "video is not awaiting upload");
        }
        // TODO next step: publish one transcode message per resolution to RabbitMQ.
    }

    private boolean uploadExists(UUID videoId) {
        try {
            // statObject reads metadata only; getObject would download the whole file.
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(storage.bucket())
                    .object(StorageKeys.sourceKey(videoId))
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            // The one failure we expect and can answer: the client never uploaded.
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw new IllegalStateException("storage error checking upload for video " + videoId, e);
        } catch (MinioException e) {
            // Anything else means storage is broken, not the request: fail loudly (500).
            throw new IllegalStateException("storage error checking upload for video " + videoId, e);
        }
    }
}
