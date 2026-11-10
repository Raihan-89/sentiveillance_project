package com.raihan.sentiveillance_project.model;

import lombok.Getter;
import lombok.Setter;

/**
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 10 November, 2026
 */
@Getter
@Setter
public class Faces {
    /**
     * Identification threshold (0-100+ in SDK score units). A tracked face is reported as
     * a known employee only when the match score is at least this. Higher = fewer false
     * accepts, more misses. 48 corresponds to roughly a 0.001% FAR in Neurotec's scale.
     */
    private int matchingThreshold = 48;
    /** How many watch-list candidates to return per tracked subject. */
    private int matchingMaximalResultCount = 5;
    /** Minimum face image quality (0-100) before a template is extracted from a frame. */
    private int qualityThreshold = 30;
    /**
     * Minimum distance between the eyes, in pixels, for a face to be usable. Ceiling
     * cameras give small faces; lowering this detects further away, at lower accuracy.
     */
    private int minimalInterOcularDistance = 24;
    /** Template size used for the surveillance engine: COMPACT, SMALL, MEDIUM or LARGE. */
    private String templateSize = "LARGE";
}
