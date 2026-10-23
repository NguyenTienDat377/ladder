package com.ladder.video;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface VideoResolutionRepository extends JpaRepository<VideoResolution, UUID> {

    // Upsert, not insert: a job redelivered after a crash (or a retried /complete)
    // re-runs a rendition that may already have a row. A plain INSERT would hit
    // uq_video_resolution and dead-letter a job that actually succeeded.
    // Native SQL because JPQL has no ON CONFLICT; the id comes from the column's
    // DEFAULT uuidv7().
    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = """
            INSERT INTO video_resolutions (video_id, resolution, object_storage_path, segment_count, bitrate_kbps)
            VALUES (:videoId, :resolution, :objectStoragePath, :segmentCount, :bitrateKbps)
            ON CONFLICT (video_id, resolution) DO UPDATE SET
                object_storage_path = EXCLUDED.object_storage_path,
                segment_count       = EXCLUDED.segment_count,
                bitrate_kbps        = EXCLUDED.bitrate_kbps
            """)
    int upsert(UUID videoId, String resolution, String objectStoragePath, int segmentCount, int bitrateKbps);
}
