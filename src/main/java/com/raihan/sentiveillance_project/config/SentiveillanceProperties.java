package com.raihan.sentiveillance_project.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All tunables for the single-source pipeline.
 *
 * <p>Deliberately shaped so that moving to 20-30 cameras later only means turning
 * {@link Source} into a list: nothing else in the code assumes "exactly one".
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "sentiveillance")
public class SentiveillanceProperties {

    private Sdk sdk = new Sdk();
    private Source source = new Source();
    private Faces faces = new Faces();
    private Enrollment enrollment = new Enrollment();
    private Snapshots snapshots = new Snapshots();

    @Getter
    @Setter
    public static class Sdk {
        /** Absolute path to SentiVeillance_10_1_SDK. Resolved by SdkBootstrap at startup. */
        private String root;
        /**
         * Trial mode. The SDK ships Bin/Licenses/TrialFlag.txt = TRUE, and pgd.conf has
         * trial = true, so this must stay true until you activate a purchased license.
         */
        private boolean trialMode = true;
        /** Licensing service address passed to NLicense.obtain. "/local" = the local pgd daemon. */
        private String licenseServer = "/local";
        /** Port passed to NLicense.obtain. 5000 is the pgd default. */
        private int licensePort = 5000;
        /**
         * Force the licensing client to use HTTPS when talking to Neurotechnology's
         * servers. Null leaves the SDK default alone. Mirrors the "https" key in pgd.conf.
         */
        private Boolean useHttps;
        /**
         * Where the licensing client caches obtained licences (a trial licence is
         * downloaded and cached here). Null leaves the SDK default alone - override only
         * if the default location turns out not to be writable.
         */
        private String licenseStoragePath;

        /**
         * Run detection on an NVIDIA GPU. Needs a CUDA-capable card plus the TensorRT
         * libraries that ship in Lib/Linux_x86_64. Leave false to run on CPU.
         */
        private boolean useGpu = false;
    }

    @Getter
    @Setter
    public static class Source {
        public enum Type {
            /** A video file on disk - read through the Media device plugin's FileName parameter. */
            VIDEO_FILE,
            /** A network camera - read through the Media device plugin's Url parameter (RTSP). */
            IP_CAMERA
        }

        private Type type = Type.VIDEO_FILE;
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
        /** Also run the vehicle/human detector, so people are tracked even with no visible face. */
        private boolean detectPeople = true;
        /** Start the source as soon as the application is up. */
        private boolean autoStart = true;
    }

    @Getter
    @Setter
    public static class Faces {
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
        /** Only raise subject events once facial details have been extracted. */
        private boolean withDetailsOnly = false;
    }

    @Getter
    @Setter
    public static class Enrollment {
        /** Enroll every image in this directory into the watch list at startup. */
        private boolean enabled = true;
        /** Directory holding Employee_<number>_<name>.<ext> photos. */
        private String photosDirectory = "my_files";
        /** Re-extract templates for employees already stored in the database. */
        private boolean reEnrollExisting = false;
    }

    @Getter
    @Setter
    public static class Snapshots {
        /** Write a JPEG of the frame for every subject that disappears. */
        private boolean enabled = true;
        /** Where those JPEGs go. */
        private String directory = "var/snapshots";
    }
}
