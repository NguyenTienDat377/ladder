package com.ladder.video;

import java.security.Principal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ladder.security.CurrentUser;
import com.ladder.video.dto.SearchPage;
import com.ladder.video.dto.VideoCreateRequest;
import com.ladder.video.dto.VideoDetailsResponse;
import com.ladder.video.dto.VideoPage;
import com.ladder.video.dto.VideoUpdateRequest;
import com.ladder.video.dto.VideoUpdateResponse;
import com.ladder.video.dto.VideoCreateResponse;
import com.ladder.video.dto.ViewCountResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.GetMapping;


@RestController
@RequestMapping("/api/v1/videos")
public class VideoController {
    private final PlaybackService playbackService;
    private final VideoService videoService;
    private final ViewCountService viewCountService;
    private final CatalogService catalog;
    private final SearchService searchService;

    public VideoController(VideoService videoService, PlaybackService playbackService,
                           ViewCountService viewCountService, CatalogService catalog,
                           SearchService searchService) {
        this.videoService = videoService;
        this.playbackService = playbackService;
        this.viewCountService = viewCountService;
        this.catalog = catalog;
        this.searchService = searchService;
    }

    // The home feed. "before" = the nextBefore of the previous page; absent = the first page.
    @GetMapping
    public VideoPage feed(@RequestParam(required = false) UUID before,
                          @RequestParam(defaultValue = "" + CatalogService.DEFAULT_LIMIT) int limit) {
        return catalog.feed(before, limit);
    }

    @GetMapping("/search")
    public SearchPage search(@RequestParam(defaultValue = "") String q,
                             @RequestParam(defaultValue = "0") int offset,
                             @RequestParam(defaultValue = "" + CatalogService.DEFAULT_LIMIT) int limit) {
        return searchService.search(q, offset, limit);
    }

    @GetMapping("/{id}")
    public VideoDetailsResponse details(@PathVariable UUID id, Principal principal) {
        return catalog.details(id, CurrentUser.idOrNull(principal));
    }

    @PatchMapping("/{id}")
    public VideoUpdateResponse update(@PathVariable UUID id, @Valid @RequestBody VideoUpdateRequest request,
                                      Principal principal) {
        return catalog.update(id, request, CurrentUser.idOrNull(principal));
    }

    // Viewer = the login when there is one, else the client address (so
    // everyone behind one NAT counts as one anonymous viewer).
    @PostMapping("/{id}/view")
    public ViewCountResponse recordView(@PathVariable UUID id, HttpServletRequest request, Principal principal) {
        UUID userId = CurrentUser.idOrNull(principal);
        String viewer = userId != null ? userId.toString() : request.getRemoteAddr();
        return new ViewCountResponse(viewCountService.recordView(id, viewer, userId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VideoCreateResponse createVideo(@Valid @RequestBody VideoCreateRequest request, Principal principal) {
        return videoService.createVideo(request, CurrentUser.idOrNull(principal));
    }

    // 202 Accepted, not 200: the transcode happens later, asynchronously.
    @PostMapping("/{id}/complete")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void completeUpload(@PathVariable UUID id, Principal principal) {
        videoService.completeUpload(id, CurrentUser.idOrNull(principal));
    }

    // Proxied through the API instead of a presigned storage URL: a presigned
    // URL changes on every request, so no browser or CDN could ever cache the
    // picture. Here the bytes carry an ETag (a repeat visit gets a 304) and a
    // Cache-Control that matches who may see the video.
    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@PathVariable UUID id, Principal principal) {
        PlaybackService.Thumbnail thumbnail = playbackService.thumbnail(id, CurrentUser.idOrNull(principal));
        CacheControl cache = thumbnail.shareable()
                // An hour: how long a cache can keep serving it after the video turns PRIVATE.
                ? CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic()
                : CacheControl.noCache().cachePrivate();
        return ResponseEntity.ok()
                .cacheControl(cache)
                .eTag(DigestUtils.md5DigestAsHex(thumbnail.bytes()))
                .contentType(MediaType.IMAGE_JPEG)
                .body(thumbnail.bytes());
    }

    @GetMapping(value = "/{id}/master.m3u8", produces = "application/vnd.apple.mpegurl")
    public String masterPlaylist(@PathVariable UUID id, Principal principal) {
        return playbackService.masterPlaylist(id, CurrentUser.idOrNull(principal));
    }
    @GetMapping(value = "/{id}/{resolution}/playlist.m3u8", produces = "application/vnd.apple.mpegurl")
    public String renditionPlaylist(@PathVariable UUID id, @PathVariable String resolution, Principal principal) {
        return playbackService.renditionPlaylist(id, resolution, CurrentUser.idOrNull(principal));
    }

}
