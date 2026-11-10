package com.raihan.sentiveillance_project.model;

import lombok.Getter;
import lombok.Setter;

/**
 * The browser preview feed.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 10 November, 2026
 */
@Getter
@Setter
public class Live {
    /**
     * Minimum gap between two encoded preview frames. 200ms = 5 fps, which looks fine
     * for monitoring and costs well under half of what 80ms (12 fps) did. Lower it only
     * if you have CPU to spare or a smaller source.
     */
    private long previewIntervalMillis = 200;
    /**
     * How long after a browser last asked for a frame encoding continues. With nothing
     * watching, the capture listener does no work at all.
     */
    private long demandTimeoutMillis = 5_000;
    /**
     * Longest edge, in pixels, of the frame sent to the browser. The source is portrait
     * 2160x3840, which is both far more detail than a monitoring view needs and ~400 KB
     * per JPEG; scaling to this before drawing the boxes keeps the draw cheap and the
     * wire small. 0 sends the frame at full resolution.
     */
    private int previewMaxHeight = 900;
}
