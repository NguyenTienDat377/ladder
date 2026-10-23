package com.ladder.transcode;

import java.util.UUID;

// One message = one rendition of one video = one video_resolutions row.
public record TranscodeJob(UUID videoId, String resolution) {
}
