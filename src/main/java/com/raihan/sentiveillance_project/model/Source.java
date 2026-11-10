package com.raihan.sentiveillance_project.model;

import com.raihan.sentiveillance_project.enums.SourceType;
import lombok.Getter;
import lombok.Setter;

/**
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 10 November, 2026
 */
@Getter
@Setter
public class Source {

    private SourceType type = SourceType.VIDEO_FILE;
    /** Friendly name used in logs and stored on every detection row. */
    private String name = "source-1";
    /** Path to the video file. Used when type = VIDEO_FILE. */
    private String file;
    /** RTSP/HTTP URL of the camera. Used when type = IP_CAMERA. */
    private String url;
    /**
     * Optional Media-plugin flags, e.g. "PreferVlc". Values come from the SDK's own
     * source dialog; on Linux they are rarely needed.
     */
    private String flags;

    /** Also run the body detector, adding the {@code VEHICLES_AND_HUMANS} modality. */
    private boolean detectPeople = false;

    /**
     * Capture as soon as the application is up. Default false: the engine is built and
     * the source attached at startup, then it waits to be triggered from the dashboard
     * (or POST /api/surveillance/start). The default matches application.properties so a
     * missing property cannot silently start capturing.
     */
    private boolean autoStart = false;

    /**
     * Where videos chosen from the dashboard are kept. They are written here and handed to
     * the SDK by path, so the file has to outlive the upload request - a temp file would be
     * gone before the engine was finished with it.
     */
    private String uploadsDirectory = "var/uploads";
}
