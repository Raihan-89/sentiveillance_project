package com.raihan.sentiveillance_project.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One IP camera the dashboard knows how to switch to.
 *
 * <p>Why a table at all, when {@code sentiveillance.source.url} already exists: that property
 * is read once at boot and describes a single source, so changing cameras meant editing a file
 * and restarting - and switching to a video file erased the URL for the rest of the process.
 * Keeping cameras here makes them selectable at runtime and survives a restart, which is also
 * how the SDK's own Java web sample does it ({@code SourceEntity}: id, display name, url,
 * flags).
 *
 * <p>The last-seen columns are a convenience, not state the pipeline depends on: they let the
 * dashboard show what a camera was when it last answered, before anything is connected.
 *
 * <p><b>The URL holds the camera password in plain text</b>, exactly as
 * {@code application.properties} does. It is never sent to the browser unmasked (see
 * {@link com.raihan.sentiveillance_project.sdk.CameraUrls#mask}), but anybody who can read the
 * database can read the camera credentials - so give the app a dedicated, read-only camera
 * account rather than the camera's admin login.
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
