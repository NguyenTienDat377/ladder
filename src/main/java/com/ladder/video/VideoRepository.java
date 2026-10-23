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

}
