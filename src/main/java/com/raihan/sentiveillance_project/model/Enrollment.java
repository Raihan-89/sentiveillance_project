package com.raihan.sentiveillance_project.model;

import lombok.Getter;
import lombok.Setter;

/**
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 10 November, 2026
 */
@Getter
@Setter
public class Enrollment {
    private boolean enabled = false;
    private String photosDirectory = "my_files";
    private boolean reEnrollExisting = false;
    private boolean captureUnknownSubjects = true;
}
