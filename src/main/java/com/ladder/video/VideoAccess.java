package com.ladder.video;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.ladder.security.Permissions;

/**
 * The one answer to "may this person watch this video?", shared by playback
 * and view counting so they can't disagree.
 *
 * Every refusal is a 404, never a 403: "you may not see this" must look the
 * same as "there is no such video", or the status code would tell a stranger
 * which private video ids exist.
 */
@Component
public class VideoAccess {
    private final VideoRepository videos;
    private final Permissions permissions;

    public VideoAccess(VideoRepository videos, Permissions permissions) {
        this.videos = videos;
        this.permissions = permissions;
    }

    /** @param userId the logged-in user, or null for an anonymous visitor */
    public Video requireWatchable(UUID videoId, UUID userId) {
        Video video = videos.findById(videoId)
                .filter(v -> "READY".equals(v.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "video not found"));
        // PUBLIC and UNLISTED: anyone holding the link. (UNLISTED differs from
        // PUBLIC only in being left out of feeds and search.)
        if ("PRIVATE".equals(video.getVisibility())
                && !permissions.has(userId, video.getChannelId(), "video.view_private")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "video not found");
        }
        return video;
    }
}
