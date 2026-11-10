package com.raihan.sentiveillance_project.model;

import lombok.Getter;
import lombok.Setter;

/**
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 10 November, 2026
 */
@Getter
@Setter
public class Snapshots {
    /**
     * How much context to keep around a subject when cropping their snapshot, as a
     * fraction of the detection box on each axis. 0 crops tight to the box; 0.25 adds a
     * quarter of its width and height on every side, which is usually enough to show who
     * the person is without burying them in the rest of the scene.
     */
    private double subjectPadding = 0.25;
    /**
     * Context kept around the face when cropping a snapshot, as a fraction of the face
     * box on each side. Faces cropped tight to their bounding box are hard to recognise,
     * so this is deliberately generous - 0.6 keeps hair, chin and some shoulder.
     */
    private double facePadding = 0.6;
    /** Write a JPEG of the frame for every subject that disappears. */
    private boolean enabled = true;
    /** Where those JPEGs go. */
    private String directory = "var/snapshots";
}
