package com.ladder.transcode;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.ladder.storage.StorageKeys;
import com.ladder.storage.StorageProperties;
import com.ladder.transcode.FfmpegTranscoder.TranscodeException;
import com.ladder.video.VideoRepository;
import com.ladder.video.VideoResolutionRepository;

import io.minio.MinioClient;

// The worker's ordering and failure rules, with every collaborator mocked: no
// ffmpeg, no storage, no database. (The real ffmpeg is covered by FfmpegMediaTest.)
class TranscodeWorkerTest {

    private final FfmpegTranscoder transcoder = mock(FfmpegTranscoder.class);
    private final MinioClient minio = mock(MinioClient.class);
    private final VideoResolutionRepository resolutions = mock(VideoResolutionRepository.class);
    private final VideoRepository videos = mock(VideoRepository.class);
    private final TranscodeCoordinator coordinator = mock(TranscodeCoordinator.class);
    private final TranscodeWorker worker = new TranscodeWorker(transcoder, minio,
            new StorageProperties("http://x", "a", "b", "bucket", "http://o"), resolutions, videos, coordinator);
    private final UUID video = UUID.randomUUID();

    @Test
    void lowestRungRecordsDurationAndThumbnailBeforeItsRowIsUpserted() throws Exception {
        when(transcoder.probeDurationSecs(any())).thenReturn(12);
        // The real captureThumbnail writes the file; the upload reads it.
        doAnswer(call -> Files.writeString(call.<Path>getArgument(1), "jpeg"))
                .when(transcoder).captureThumbnail(any(), any(), any());

        worker.handle(new TranscodeJob(video, "240p"));

        // A video turns READY once all four rows exist, so these must come first.
        InOrder order = inOrder(videos, resolutions, coordinator);
        order.verify(videos).setDuration(video, 12);
        order.verify(videos).setThumbnailPath(video, StorageKeys.thumbnailKey(video));
        order.verify(resolutions).upsert(eq(video), eq("240p"), any(), anyInt(), anyInt());
        order.verify(coordinator).onRenditionDone(video);
    }

    @Test
    void aFailedProbeDoesNotFailTheTranscode() {
        when(transcoder.probeDurationSecs(any())).thenThrow(new TranscodeException("ffprobe broke"));

        worker.handle(new TranscodeJob(video, "240p"));

        verify(videos, never()).setDuration(any(), anyInt());
        verify(videos, never()).setThumbnailPath(any(), any());
        verify(resolutions).upsert(eq(video), eq("240p"), any(), anyInt(), anyInt());
        verify(coordinator).onRenditionDone(video);
    }

    @Test
    void aFailedThumbnailKeepsTheDurationAndStillFinishesTheJob() {
        when(transcoder.probeDurationSecs(any())).thenReturn(30);
        doThrow(new TranscodeException("no frame")).when(transcoder).captureThumbnail(any(), any(), any());

        worker.handle(new TranscodeJob(video, "240p"));

        verify(videos).setDuration(video, 30);
        verify(videos, never()).setThumbnailPath(any(), any());
        verify(resolutions).upsert(eq(video), eq("240p"), any(), anyInt(), anyInt());
    }

    @Test
    void higherRungsLeaveTheMediaAlone() {
        worker.handle(new TranscodeJob(video, "480p"));

        verify(transcoder, never()).probeDurationSecs(any());
        verify(transcoder, never()).captureThumbnail(any(), any(), any());
        verify(videos, never()).setDuration(any(), anyInt());
        verify(resolutions).upsert(eq(video), eq("480p"), any(), anyInt(), anyInt());
    }
}
