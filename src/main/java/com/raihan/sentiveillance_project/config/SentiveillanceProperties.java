package com.raihan.sentiveillance_project.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All tunables for the single-source pipeline.
 *
 * <p>Deliberately shaped so that moving to 20-30 cameras later only means turning
 * {@link Source} into a list: nothing else in the code assumes "exactly one".
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

    /**
     * @author Md. Raihan Shikder (Raihan-89)
     * @date 8 November, 2026
     */
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

    /**
     * @author Md. Raihan Shikder (Raihan-89)
     * @date 8 November, 2026
     */
    @Getter
    @Setter
    public static class Source {
        /**
         * @author Md. Raihan Shikder (Raihan-89)
         * @date 8 November, 2026
         */
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
        /**
         * Also run the body detector, adding the {@code VEHICLES_AND_HUMANS} modality.
         *
         * <p>Off by default, so the engine runs on faces alone. With it on, the rectangle the
         * engine reports for a subject is their whole body, which is what put a tall box around
         * each person instead of their face, and the body detector is also the expensive half of
         * the pipeline. Measured on the same clip:
         * <pre>
         *   faces + bodies   boxes ~1020x3624 (aspect 0.28)   detector lag ~4700 ms
         *   faces only       boxes ~170x233   (aspect 0.75)   detector lag ~214 ms
         * </pre>
         *
         * <p>What is given up: somebody whose face is turned away is not tracked at all, and
         * range shortens, because a face covers far fewer pixels than a body - at distance a
         * person may be plainly visible as a body while their face is too small to detect.
         * Turn this on if tracking people regardless of head position matters more than either
         * latency or face-shaped boxes.
         */
        private boolean detectPeople = false;
        /**
         * Capture as soon as the application is up. Default false: the engine is built and
         * the source attached at startup, then it waits to be triggered from the dashboard
         * (or POST /api/surveillance/start). The default matches application.properties so a
         * missing property cannot silently start capturing.
         */
        private boolean autoStart = false;
    }

    /**
     * @author Md. Raihan Shikder (Raihan-89)
     * @date 8 November, 2026
     */
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
    }

    /**
     * The browser preview feed.
     *
     * <p>Encoding the preview competes with detection for CPU, and the source here is
     * 2160x3840 - roughly 8 megapixels per JPEG. Measured with the detector at
     * {@code detectorScaling=ONE}: no viewer attached, detection lag reached 3.5s; with a
     * viewer pulling frames every 80ms it reached 4.9s. The frame interval is therefore a
     * real tuning knob, not cosmetic.
     *
     * @author Md. Raihan Shikder (Raihan-89)
     * @date 9 November, 2026
     */
    @Getter
    @Setter
    public static class Live {
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

    /**
     * Person-related detection, and the switches that keep vehicles out of the pipeline.
     *
     * <p>The SDK's body detector is one model that emits PERSON, CAR, BUS, TRUCK and BIKE -
     * it cannot be told to look for people only. So "humans only" is enforced in two places:
     * the vehicle attribute extractors below are left off, and every event whose object type
     * is not PERSON is dropped before it reaches the database or the dashboard.
     *
     * @author Md. Raihan Shikder (Raihan-89)
     * @date 9 November, 2026
     */
    @Getter
    @Setter
    public static class People {
        /**
         * Discard detections classified as CAR, BUS, TRUCK or BIKE. Leave true - it is what
         * makes this a people-only pipeline. Setting it to false records whatever the body
         * detector reports, vehicles included.
         */
        private boolean humansOnly = true;
        /**
         * Confidence the body detector needs before reporting a box (SDK units).
         * Raise it if furniture or shadows are being tracked as people.
         */
        private int detectorThreshold = 50;
        /**
         * How many scales the object detector runs: ONE, TWO, THREE or FOUR.
         *
         * <p>This is the single control over <em>how far away</em> a person is still detected,
         * and it trades directly against latency. Measured on this machine (CPU, 2160x3840,
         * same clip, cap at 0.33s):
         * <pre>
         *   ONE    4 subjects    8 recognised   peak lag  2.4s
         *   TWO    5 subjects   14-16 recognised   peak lag  5-7s
         *   THREE  5 subjects   16 recognised   peak lag 10.7s
         *   FOUR   7-8 subjects  7 recognised   peak lag 17-19s
         * </pre>
         * Subject count and recognition count are the reproducible signals; the smallest box
         * the detector reports is not - it came out 180px, 233px and 233px across three runs
         * at TWO, because it depends on which frames happened to be sampled. The one place
         * that figure was stable is FOUR, which reported an 82px-wide person in two
         * independent runs against 233px everywhere else: that setting really does see about
         * three times further, at the price of an overlay that is no longer a live view.
         *
         * <p>TWO is the chosen balance: it consistently finds one more subject than ONE and
         * close to double the identifications, while the dashboard stays usable.
         *
         * <p>The way out of the trade is fewer pixels per frame, not a different setting -
         * halve the source resolution and every row above gets roughly twice as fast.
         */
        private String detectorScaling = "TWO";
        /**
         * Minimum seconds between frames handed to the detector - a frame-rate cap, and the
         * difference between an overlay that keeps up and one that drifts further behind the
         * longer the source runs. 0 leaves the SDK default (0.17s) alone.
         *
         * <p>Measured peak lag over the same clip at {@code detectorScaling=ONE}: 0.17s gave
         * ~4900ms, 0.33s gave ~2270ms, 0.50s gave ~810ms. Both enrolled people were still
         * recognised at every setting; what shrinks is the number of samples per visit.
         *
         * <p>Note that it also costs range: at {@code detectorScaling=TWO} the smallest person
         * detected was 180px wide with this at 0.33s, but 233px with it at 0.50s. Fewer frames
         * examined means a subject is first caught when they are already closer. 0.33s is the
         * point where latency improves without measurably shortening range.
         */
        private float detectorMinFrameTimeSeconds = 0.33f;
        /** Extract upper/lower clothing colour per tracked person. */
        private boolean detectClothingDetails = true;
        /** Classify a face/head covering - mask, hat, hood. */
        private boolean detectFaceAndHeadCoverings = true;
        /**
         * Estimate an age group (child / teenager / adult / senior). Off by default: it
         * loads another model and costs CPU per track.
         */
        private boolean estimateAgeGroup = false;
    }

    /**
     * @author Md. Raihan Shikder (Raihan-89)
     * @date 8 November, 2026
     */
    @Getter
    @Setter
    public static class Enrollment {
        /**
         * Scan {@link #photosDirectory} at startup and build a template from every photo.
         *
         * <p>Off by default: people are enrolled from the video instead. The engine detects
         * somebody, fails to match them, queues them with the face template it captured during
         * the visit, and a name is all that is needed to add them to the watch list - see
         * {@code VideoEnrollmentService}. That template comes from the real camera at the real
         * angle, which measured better than a photo-derived one (score 234 against 109-212).
         *
         * <p>With this off, startup neither reads the photo directory nor constructs an
         * {@code NBiometricEngine} at all; the watch list is simply loaded from the database.
         * Turn it on if you want to seed the watch list from files.
         */
        private boolean enabled = false;
        /** Directory holding Employee_<number>_<name>.<ext> photos. Only read when enabled. */
        private String photosDirectory = "my_files";
        /** Re-extract templates for employees already stored in the database. */
        private boolean reEnrollExisting = false;
        /**
         * Keep the face template of anybody who leaves the frame unidentified, so they can be
         * named from the dashboard and enrolled without a photograph. Only tracks that
         * actually produced a face are kept.
         */
        private boolean captureUnknownSubjects = true;
    }

    /**
     * @author Md. Raihan Shikder (Raihan-89)
     * @date 8 November, 2026
     */
    @Getter
    @Setter
    public static class Snapshots {
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
}
