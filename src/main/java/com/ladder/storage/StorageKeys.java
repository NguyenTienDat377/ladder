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

    // Ends with "/": callers append file names (playlist.m3u8, segment_000.ts).
    public static String renditionPrefix(UUID videoId, String resolution) {
        return "videos/" + videoId + "/" + resolution + "/";
    }
}
