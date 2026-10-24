package com.ladder.storage;

import java.util.UUID;

// The one place that knows how object keys are laid out in the bucket.
// Keys are always derived from our own ids, never from client input.
public final class StorageKeys {
    private StorageKeys() {
    }

    public static String sourceKey(UUID videoId) {
        return "uploads/" + videoId + "/source.mp4";
    }

    // The file a player starts from; sits next to the rendition folders so its
    // entries can be relative ("480p/playlist.m3u8").
    public static String masterPlaylistKey(UUID videoId) {
        return "videos/" + videoId + "/master.m3u8";
    }

    // One JPEG per video, written by the transcode worker.
    public static String thumbnailKey(UUID videoId) {
        return "videos/" + videoId + "/thumbnail.jpg";
    }

    // Ends with "/": callers append file names (playlist.m3u8, segment_000.ts).
    public static String renditionPrefix(UUID videoId, String resolution) {
        return "videos/" + videoId + "/" + resolution + "/";
    }
}
