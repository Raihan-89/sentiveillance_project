package com.raihan.sentiveillance_project.config;

import com.raihan.sentiveillance_project.model.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All tunables for the single-source pipeline.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 8 November, 2026
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "sentiveillance")
public class SentiveillanceProperties {

    private Sdk sdk = new Sdk();
    private Source source = new Source();
    private Faces faces = new Faces();
    private People people = new People();
    private Live live = new Live();
    private Enrollment enrollment = new Enrollment();
    private Snapshots snapshots = new Snapshots();
}
