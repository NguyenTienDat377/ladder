package com.ladder.video;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ladder.video.dto.VideoCreateRequest;
import com.ladder.video.dto.VideoCreateResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/videos")
public class VideoController {
    private final VideoService videoService;

    public VideoController(VideoService videoService) {
        this.videoService = videoService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VideoCreateResponse createVideo(@Valid @RequestBody VideoCreateRequest request) {
        return videoService.createVideo(request);
    }

    // 202 Accepted, not 200: the transcode happens later, asynchronously.
    @PostMapping("/{id}/complete")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void completeUpload(@PathVariable UUID id) {
        videoService.completeUpload(id);
    }
}
