package com.ladder.video;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Written only through VideoResolutionRepository.upsert; mapped as an entity so
// the playback step can read renditions back.
@Entity
@Table(name = "video_resolutions")
@Getter
@NoArgsConstructor
public class VideoResolution {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID videoResolutionId;

    @Column(nullable = false)
    private UUID videoId;

    @Column(nullable = false)
    private String resolution;

    @Column(nullable = false)
    private String objectStoragePath;

    private Integer segmentCount;

    private Integer bitrateKbps;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
