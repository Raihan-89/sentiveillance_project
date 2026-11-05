package com.raihan.sentiveillance_project;

import com.raihan.sentiveillance_project.bootstrap.SdkBootstrap;
import com.raihan.sentiveillance_project.config.SentiveillanceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SentiveillanceProperties.class)
public class SentiveillanceProjectApplication {

    public static void main(String[] args) {
        // Must happen before Spring touches any com.neurotec.* class: their static
        // initialisers load the native libraries through JNA.
        SdkBootstrap.initLibraryPath();
        SpringApplication.run(SentiveillanceProjectApplication.class, args);
    }
}
