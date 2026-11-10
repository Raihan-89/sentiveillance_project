package com.raihan.sentiveillance_project.records;

/**
 * One detection rectangle, in source-frame pixel coordinates, waiting to be drawn.
 *
 * @param left       box position in the full-resolution frame
 * @param label      what to write above the box - a name and score, or "unknown"
 * @param identified whether this subject matched the watch list, which picks the colour
 * @param atMillis   when the box was last refreshed, so stale ones can be dropped
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 9 November, 2026
 */
public record OverlayBox(int left, int top, int width, int height,
                         String label, boolean identified, long atMillis) {
}
