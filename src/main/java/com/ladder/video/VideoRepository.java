package com.ladder.video;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface VideoRepository extends JpaRepository<Video, UUID> {

    // Check-and-set in one statement: two concurrent /complete calls can't both
    // see UPLOADING and both queue the transcode. Returns rows changed (0 or 1).
    @Modifying
    @Transactional
    @Query("update Video v set v.status = 'PROCESSING' where v.videoId = :id and v.status = 'UPLOADING'")
    int markProcessing(UUID id);

    // Undo markProcessing when queueing fails, so the client can retry /complete.
    @Modifying
    @Transactional
    @Query("update Video v set v.status = 'UPLOADING' where v.videoId = :id and v.status = 'PROCESSING'")
    int revertToUploading(UUID id);

    // Called when a transcode job is dead-lettered after its retries run out.
    @Modifying
    @Transactional
    @Query("update Video v set v.status = 'FAILED' where v.videoId = :id and v.status = 'PROCESSING'")
    int markFailed(UUID id);

    // Called once every rendition exists and the master playlist is written.
    // The status condition means only one caller can succeed, even if two
    // workers finish the last renditions at the same moment.
    @Modifying
    @Transactional
    @Query("update Video v set v.status = 'READY' where v.videoId = :id and v.status = 'PROCESSING'")
    int markReady(UUID id);

    // Adds to the stored count in the database, never "set to N": the flush
    // can't clobber views another flush added. Native because the field is
    // mapped read-only, which JPQL updates don't accept.
    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = "UPDATE videos SET view_count = view_count + :delta WHERE video_id = :id")
    int addViews(UUID id, long delta);
}
