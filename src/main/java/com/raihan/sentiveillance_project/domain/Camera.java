package com.raihan.sentiveillance_project.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One IP camera the dashboard knows how to switch to.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 10 November, 2026
 */
@Entity
@Table(name = "ip_camera", uniqueConstraints = @UniqueConstraint(name = "uk_ip_camera_name", columnNames = "name"))
@Getter
@Setter
@NoArgsConstructor
public class Camera {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Label shown in the UI and stored as {@code source_name} on every detection row. */
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** Full stream URL, credentials included, as handed to the Media plugin's Url parameter. */
    @Column(name = "url", nullable = false, length = 1000)
    private String url;

    /** Optional Media-plugin flags for this camera only, e.g. {@code DoNotUseVlc}. */
    @Column(name = "flags", length = 200)
    private String flags;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** When a test or an activation last reached this camera. */
    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    /** When this camera was last made the live source. */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /** Outcome of the last test or activation, shown in the UI as-is. */
    @Column(name = "last_result", length = 500)
    private String lastResult;

    /** True when {@link #lastResult} describes a success. */
    @Column(name = "last_ok")
    private Boolean lastOk;

    // --- what the SDK reported the last time this camera answered -----------------

    @Column(name = "last_width")
    private Integer lastWidth;

    @Column(name = "last_height")
    private Integer lastHeight;

    @Column(name = "last_frame_rate")
    private Double lastFrameRate;

    @Column(name = "last_codec", length = 40)
    private String lastCodec;

    @Column(name = "last_make", length = 200)
    private String lastMake;

    @Column(name = "last_model", length = 200)
    private String lastModel;
}
