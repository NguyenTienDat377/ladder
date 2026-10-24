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
import lombok.Setter;

@Entity
@Table(name = "videos")
@Getter
@Setter
@NoArgsConstructor
public class Video {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID videoId;

    @Column(nullable = false)
    private UUID channelId;

    @Column(nullable = false)
    private String title;

    private String description;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private String visibility;

    // Read-only: only the view-count flush writes this column, with an
    // "x = x + n" statement. If JPA could write it, saving a Video loaded
    // before a flush would overwrite the flushed views with its stale copy.
    @Column(insertable = false, updatable = false)
    private long viewCount;

    // Left out of INSERT/UPDATE so the column's DEFAULT now() applies;
    // otherwise Hibernate would send NULL and violate NOT NULL.
    @Column(insertable = false, updatable = false)
    private Instant createdAt;
}
