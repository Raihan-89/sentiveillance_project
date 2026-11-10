package com.raihan.sentiveillance_project.sdk;

import com.neurotec.biometrics.NTemplateSize;
import com.neurotec.devices.NCamera;
import com.neurotec.geometry.NRect;
import com.neurotec.images.NImage;
import com.neurotec.images.NImageFormat;
import com.neurotec.surveillance.NSEDMatchResult;
import com.neurotec.surveillance.NSurveillance;
import com.neurotec.surveillance.NSurveillanceEvent;
import com.neurotec.surveillance.NSurveillanceEventDetails;
import com.neurotec.surveillance.NSurveillanceEventType;
import com.neurotec.surveillance.NSurveillanceListener;
import com.neurotec.surveillance.NSurveillanceObjectTypeDetails;
import com.neurotec.surveillance.NSurveillanceSource;
import com.raihan.sentiveillance_project.config.SentiveillanceProperties;
import com.raihan.sentiveillance_project.detection.DetectionRecorder;
import com.raihan.sentiveillance_project.records.CameraDetails;
import com.raihan.sentiveillance_project.records.Stats;
import com.raihan.sentiveillance_project.records.SubjectEventSnapshot;
import com.raihan.sentiveillance_project.domain.Employee;
import com.raihan.sentiveillance_project.service.UnknownCandidateService;
import com.raihan.sentiveillance_project.service.CandidateFaceMatcher;
import com.raihan.sentiveillance_project.enums.SourceType;
import com.raihan.sentiveillance_project.live.LiveEventStream;
import com.raihan.sentiveillance_project.service.LiveFrameService;
import com.raihan.sentiveillance_project.service.RecognizedPeopleService;
import com.raihan.sentiveillance_project.model.Faces;
import com.raihan.sentiveillance_project.model.People;
import com.raihan.sentiveillance_project.model.Source;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the one {@link NSurveillance} instance and the one source attached to it.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 8 November, 2026
 */
@Slf4j
@Service
public class SurveillanceEngine {

    private final SentiveillanceProperties properties;
    private final LicenseService licenseService;
    private final SurveillanceSourceFactory sourceFactory;
    private final DetectionRecorder recorder;
    /** Feeds the browser dashboard; encodes nothing unless a page is actually watching. */
    private final LiveFrameService liveFrames;
    /** Pushes events straight to the dashboard, without waiting for the database. */
    private final LiveEventStream liveEvents;
    private final RecognizedPeopleService recognizedPeople;
    /** Review queue of people the engine saw but could not name. */
    private final UnknownCandidateService unknownCandidates;
    private final CandidateFaceMatcher candidateMatcher;

    /**
     * Single-threaded so database rows land in event order; separate from the SDK's threads
     * so a slow write never blocks frame processing.
     */
    private final ExecutorService eventExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "detection-writer"));
    /** Reuses one matcher and serializes candidate comparisons without blocking capture. */
    private final ExecutorService candidateExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "candidate-writer"));

    /** Bound early extraction work so crowded frames cannot build an unlimited backlog. */
    private final java.util.concurrent.Semaphore candidateSlots = new java.util.concurrent.Semaphore(4);

    /** First usable capture immediately; later frames only when face quality improves. */
    private final java.util.Map<Integer, CandidateCapture> candidateCaptures =
            new java.util.concurrent.ConcurrentHashMap<>();

    static final class CandidateCapture {
        volatile long nextAttempt;
        volatile boolean pending;
        volatile Long savedId;
        volatile boolean identified;
        volatile boolean suppressed;
        volatile int savedQuality = -1;
        volatile long attemptedFrame = -1;
    }

    private final ExecutorService snapshotExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "snapshot-writer"));

    /**
     * Camera tests run here rather than on the request thread: connecting to a camera that
     * is not there blocks inside the plugin's ffmpeg for as long as its own timeout, and one
     * thread doing that is enough. The lock keeps two tests from reaching the device manager
     * at once.
     */
    private final ExecutorService probeExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "camera-probe");
                t.setDaemon(true);
                return t;
            });

    private final java.util.concurrent.locks.ReentrantLock probeLock =
            new java.util.concurrent.locks.ReentrantLock();

    /** How long {@link #sourceDetails()} may serve the device description it last read. */
    private static final long DETAILS_CACHE_MILLIS = 10_000;

    /**
     * {@code sentiveillance.source.flags} as configured at boot.
     *
     * <p>Held separately because the live configuration is rewritten on every source switch:
     * a camera that specifies its own decoder flags would otherwise leave them applied to
     * whatever is attached next, and the configured default would be unrecoverable after the
     * first switch.
     */
    private final String configuredFlags;
    private volatile CameraDetails cachedDetails;
    private volatile long cachedDetailsAt;

    private NSurveillance surveillance;
    private NSurveillanceSource source;

    @Getter
    private volatile boolean running;
    /** Whether {@link #subscribe()} actually ran - listeners must only be removed if added. */
    private boolean subscribed;
    private final AtomicLong eventsSeen = new AtomicLong();
    private final AtomicLong subjectsSeen = new AtomicLong();
    private final AtomicLong identifiedSeen = new AtomicLong();
    /** Vehicle-class detections discarded by {@link #isHumanSubject}. */
    private final AtomicLong nonHumanIgnored = new AtomicLong();
    /**
     * How far behind the camera the detector currently is: milliseconds between a frame's SDK
     * timestamp and the moment its event reached us. Exposed on /api/status because this, not
     * the HTTP transport, is what decides whether the overlay looks live.
     */
    private final AtomicLong detectionLagMillis = new AtomicLong();
    /** Unknown people queued for naming since startup. */
    private final AtomicLong unknownCaptured = new AtomicLong();
    /**
     * Templates currently in the engine's watch list, counted here rather than asked of the
     * SDK.
     */
    private final java.util.concurrent.atomic.AtomicInteger watchListSize =
            new java.util.concurrent.atomic.AtomicInteger();
    /** traceIndex -> the watch-list id matched at any point during that visit. */
    private final java.util.Map<Integer, String> identityByTrace = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * templateId -> employee name, built from the watch list at start. Lets the live push name
     * the person without a database round trip on the callback thread.
     */
    private final java.util.Map<String, String> watchListNames = new java.util.concurrent.ConcurrentHashMap<>();

    // Listeners are kept as fields so the exact same instances can be unsubscribed later.
    private final NSurveillanceListener onSubjectAppeared = e -> handle(e, "SUBJECT_APPEARED");
    private final NSurveillanceListener onSubjectTrack = e -> handle(e, "SUBJECT_TRACK");
    private final NSurveillanceListener onSubjectDisappeared = e -> handle(e, "SUBJECT_DISAPPEARED");
    private final com.neurotec.event.ChangeListener onRunningChanged = e -> {
        running = surveillance != null && surveillance.isRunning();
        // The camera's own capture state just changed, so the cached description is stale.
        forgetSourceDetails();
        log.info("Surveillance running = {}", running);
    };

    public SurveillanceEngine(SentiveillanceProperties properties,
                              LicenseService licenseService,
                              SurveillanceSourceFactory sourceFactory,
                              DetectionRecorder recorder,
                              LiveFrameService liveFrames,
                              LiveEventStream liveEvents,
                              UnknownCandidateService unknownCandidates,
                              CandidateFaceMatcher candidateMatcher,
                              RecognizedPeopleService recognizedPeople) {
        this.properties = properties;
        this.licenseService = licenseService;
        this.sourceFactory = sourceFactory;
        this.recorder = recorder;
        this.liveFrames = liveFrames;
        this.liveEvents = liveEvents;
        this.unknownCandidates = unknownCandidates;
        this.candidateMatcher = candidateMatcher;
        this.recognizedPeople = recognizedPeople;
        this.configuredFlags = properties.getSource().getFlags();
    }

    /**
     * Builds the engine, loads the watch list, attaches the source and starts capturing.
     *
     * @param watchList employees whose templates should be matched against
     */
    public synchronized void start(Collection<Employee> watchList) {
        if (surveillance != null) {
            log.warn("Engine already started");
            return;
        }

        log.info("Creating NSurveillance (loading models from {}/Bin/Data) ...", properties.getSdk().getRoot());
        surveillance = new NSurveillance();

        try {
            applySettings(surveillance);
            loadWatchList(watchList);

            source = sourceFactory.create(surveillance);
            surveillance.getSources().add(source);

            subscribe();
            subscribed = true;

            if (properties.getSource().isAutoStart()) {
                log.info("Starting source '{}' ...", properties.getSource().getName());
                // startSource starts capture for this one source. surveillance.start() would
                // start every source at once - the per-source call is what scales to many cameras.
                surveillance.startSource(source);
            } else {
                log.info("autoStart is false - call POST /api/surveillance/start to begin");
            }
        } catch (RuntimeException | Error e) {
            // A half-built engine must not be left for @PreDestroy to tear down: calling
            // stop()/dispose() on one that never finished configuring aborts the JVM inside
            // glibc ("corrupted double-linked list", exit 134), which buries the real cause.
            // Release it here, in the reverse order it was built, then rethrow.
            discardAfterFailedStart();
            throw e;
        }
    }

    /** Best-effort teardown of an engine whose {@link #start} did not complete. */
    private void discardAfterFailedStart() {
        try {
            if (subscribed) {
                unsubscribe();
            }

            if (source != null) {
                surveillance.getSources().remove(source);
                disconnectCamera(source);
            }
        } catch (Throwable t) {
            log.warn("Cleanup after failed start was incomplete: {}", t.toString());
        } finally {
            try {
                surveillance.dispose();
            } catch (Throwable t) {
                log.warn("Could not dispose the partially built engine: {}", t.toString());
            }

            surveillance = null;
            source = null;
            subscribed = false;
            running = false;
            identityByTrace.clear();
            candidateCaptures.clear();
            watchListSize.set(0);
        }
    }

    /**
     * Adds a template to the running watch list, so somebody enrolled from the dashboard is
     * recognised on their next appearance instead of only after a restart.
     */
    public synchronized void addToWatchList(String templateId, byte[] template) {
        if (surveillance == null) {
            log.warn("Engine not started - {} will be picked up at the next start", templateId);
            return;
        }

        surveillance.addTemplate(templateId, template);
        log.info("Watch list now holds {} template(s)", watchListSize.incrementAndGet());
    }

    /** Records the display name for a live-enrolled person, so the push can label them. */
    public void rememberWatchListName(String templateId, String fullName) {
        watchListNames.put(templateId, fullName);
    }

    /** Swaps the running source for another video file and starts capturing it. */
    public synchronized String useVideoFile(Path file) {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IllegalArgumentException("Video file not readable: " + file);
        }

        Source next = inheritedConfig();
        next.setType(SourceType.VIDEO_FILE);
        next.setFile(file.toString());
        next.setUrl(null);
        next.setName(file.getFileName().toString());

        return switchTo(next);
    }

    /**
     * Swaps the running source for a network camera and starts capturing it.
     *
     * @param name  label stored on every detection row from this camera
     * @param url   rtsp/http URL, credentials included
     * @param flags optional Media-plugin flags, or null to use the configured default
     * @return the name now reported for the source
     */
    public synchronized String useIpCamera(String name, String url, String flags) {
        Source next = inheritedConfig();
        next.setType(SourceType.IP_CAMERA);
        next.setUrl(CameraUrls.parse(url).url());
        next.setFile(null);
        next.setName(name == null || name.isBlank() ? CameraUrls.parse(url).host() : name.trim());

        if (flags != null && !flags.isBlank()) {
            next.setFlags(flags.trim());
        }

        return switchTo(next);
    }

    /**
     * A copy of the live configuration, to be overwritten with the parts that identify the new
     * source. Carries across the settings that are a property of this deployment rather than of
     * one source - plugin flags and whether the body detector runs - so switching sources never
     * silently changes detection behaviour.
     */
    private Source inheritedConfig() {
        Source cfg = properties.getSource();
        Source next = new Source();
        next.setFlags(configuredFlags);
        next.setDetectPeople(cfg.isDetectPeople());
        next.setUploadsDirectory(cfg.getUploadsDirectory());
        next.setAutoStart(cfg.isAutoStart());

        return next;
    }

    /** Connects the replacement source, puts it in place of the current one, and starts it. */
    private String switchTo(Source next) {
        if (surveillance == null) {
            throw new IllegalStateException("Surveillance engine is not started");
        }

        Source cfg = properties.getSource();

        // Validate/connect before touching the current source or its configuration.
        NSurveillanceSource replacement = sourceFactory.create(surveillance, next);
        NSurveillanceSource previous = source;

        boolean wasRunning = running;
        var oldType = cfg.getType();

        String oldFile = cfg.getFile(), oldName = cfg.getName(), oldUrl = cfg.getUrl(), oldFlags = cfg.getFlags();

        try {
            if (previous != null) {
                surveillance.stopSource(previous);
                surveillance.getSources().remove(previous);
            }

            surveillance.getSources().add(replacement);
            source = replacement;

            cfg.setType(next.getType());
            cfg.setFile(next.getFile());
            cfg.setName(next.getName());
            cfg.setUrl(next.getUrl());
            cfg.setFlags(next.getFlags());

            liveFrames.clear();
            recognizedPeople.clear();
            identityByTrace.clear();
            candidateCaptures.clear();
            forgetSourceDetails();
            surveillance.startSource(replacement);
        } catch (RuntimeException failure) {
            // A rejected file or an unreachable camera must not strand the existing source.
            cfg.setType(oldType);
            cfg.setFile(oldFile);
            cfg.setName(oldName);
            cfg.setUrl(oldUrl);
            cfg.setFlags(oldFlags);
            source = previous;

            try {
                surveillance.stopSource(replacement);
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }

            try {
                surveillance.getSources().remove(replacement);
                if (previous != null) {
                    if (!surveillance.getSources().contains(previous)) {
                        surveillance.getSources().add(previous);
                    }

                    if (wasRunning) surveillance.startSource(previous);
                }
            } catch (RuntimeException rollback) {
                failure.addSuppressed(rollback);
            } finally {
                disconnectCamera(replacement);
                liveFrames.clear();
                recognizedPeople.clear();
                forgetSourceDetails();
            }
            throw failure;
        }

        if (previous != null) disconnectCamera(previous);
        log.info("Source switched to '{}' ({})", cfg.getName(), cfg.getType());

        return cfg.getName();
    }

    /** What the SDK knows about the attached camera, or null when no source is attached. */
    public CameraDetails sourceDetails() {
        CameraDetails cached = cachedDetails;
        if (cached != null && System.currentTimeMillis() - cachedDetailsAt < DETAILS_CACHE_MILLIS) {
            return cached;
        }

        NSurveillanceSource current;

        synchronized (this) {
            current = source;
        }

        if (current == null) {
            return null;
        }

        try {
            NCamera camera = current.getCamera();
            CameraDetails fresh = camera == null ? null
                    : SurveillanceSourceFactory.describe(camera, false);
            cachedDetails = fresh;
            cachedDetailsAt = System.currentTimeMillis();

            return fresh;
        } catch (Throwable t) {
            log.debug("Could not read camera details: {}", t.toString());
            return null;
        }
    }

    /** Invalidates {@link #sourceDetails()} - the device behind it has changed. */
    private void forgetSourceDetails() {
        cachedDetails = null;
        cachedDetailsAt = 0L;
    }

    /** Connects to a camera, reports what it is, and disconnects - without touching the running source. */
    public CameraDetails probeCamera(String name, String url, String flags, java.time.Duration timeout) {
        NSurveillance engine;

        synchronized (this) {
            engine = surveillance;
        }

        if (engine == null) {
            throw new IllegalStateException("Surveillance engine is not started");
        }

        Source cfg = inheritedConfig();
        cfg.setType(SourceType.IP_CAMERA);
        cfg.setUrl(CameraUrls.parse(url).url());
        cfg.setName(name == null || name.isBlank() ? "probe" : name.trim());

        if (flags != null && !flags.isBlank()) {
            cfg.setFlags(flags.trim());
        }

        if (!probeLock.tryLock()) {
            throw new IllegalStateException("Another camera test is still running - try again in a moment");
        }

        try {
            var future = probeExecutor.submit(() -> sourceFactory.probe(engine.getDeviceManager(), cfg));

            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                // The task owns its own cleanup, so abandoning it here is safe; it will
                // disconnect whatever it eventually gets.
                throw new IllegalStateException("Camera did not answer within "
                        + timeout.toSeconds() + "s. Check the URL, the credentials and that"
                        + " RTSP is enabled on the camera.");
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();

                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;

                throw new IllegalStateException(cause.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Camera test was interrupted");
            }
        } finally {
            probeLock.unlock();
        }
    }

    /** Starts the configured source if it is not already capturing. */
    public synchronized void startSource() {
        requireStarted();
        surveillance.startSource(source);
        forgetSourceDetails();
    }

    public synchronized void stopSource() {
        requireStarted();
        surveillance.stopSource(source);
        forgetSourceDetails();
    }

    @PreDestroy
    public synchronized void shutdown() {
        if (surveillance == null) {
            return;
        }
        log.info("Shutting down surveillance ...");

        try {
            if (subscribed) {
                unsubscribe();
                subscribed = false;
            }

            if (source != null) {
                surveillance.stopSource(source);
                surveillance.getSources().remove(source);
                disconnectCamera(source);
            }
            surveillance.stop();
        } catch (Exception e) {
            log.warn("Error while stopping surveillance: {}", e.toString());
        } finally {
            eventExecutor.shutdown();
            snapshotExecutor.shutdown();
            candidateExecutor.shutdown();
            probeExecutor.shutdownNow();

            try {
                candidateExecutor.awaitTermination(10, TimeUnit.SECONDS);
                eventExecutor.awaitTermination(10, TimeUnit.SECONDS);
                snapshotExecutor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            surveillance.dispose();
            surveillance = null;
            source = null;
            running = false;
            log.info("Totals - events: {}, subjects: {}, identified: {}, non-human discarded: {}",
                    eventsSeen.get(), subjectsSeen.get(), identifiedSeen.get(), nonHumanIgnored.get());
        }
    }

    public Stats stats() {
        return new Stats(running, eventsSeen.get(), subjectsSeen.get(), identifiedSeen.get(),
                surveillance == null ? 0 : watchListSize.get(),
                nonHumanIgnored.get(), detectionLagMillis.get(), unknownCaptured.get());
    }

    // ------------------------------------------------------------------
    // setup
    // ------------------------------------------------------------------

    private void applySettings(NSurveillance s) {
        Faces faces = properties.getFaces();

        s.setUseGPU(properties.getSdk().isUseGpu());

        // How good a face has to be before the engine bothers extracting a template.
        s.setFacesQualityThreshold((byte) faces.getQualityThreshold());
        // The single most important geometric limit for ceiling cameras: faces whose eyes are
        // closer together than this many pixels are tracked but never recognised.
        s.setFacesMinimalInterOcularDistance(faces.getMinimalInterOcularDistance());
        s.setFacesTemplateSize(NTemplateSize.valueOf(faces.getTemplateSize()));
        // The recognition decision threshold, in SDK score units.
        s.setMatchingThreshold(faces.getMatchingThreshold());
        s.setMatchingMaximalResultCount(faces.getMatchingMaximalResultCount());

        // ---- people, and keeping vehicles out ----------------------------------------
        People people = properties.getPeople();
        // The body detector is shared between people and vehicles, so its threshold is the
        // person-detection threshold here.
        s.setVehicleHumanDetectorThreshold(people.getDetectorThreshold());
        // The dominant control over detection latency - see People.detectorScaling.
        s.setDetectorScaling(com.neurotec.surveillance.NDetectorScaleCount
                .valueOf(people.getDetectorScaling()));
        // Never compute make/model/colour for a vehicle: the work is pure waste in a
        // people-only pipeline, and nothing downstream reads NVehicleDetails.
        s.setDetectVehicleDetails(false);
        // Person attributes we do want.
        applyDetectorFrameRateCap(s, people);
        s.setDetectClothingDetails(people.isDetectClothingDetails());
        s.setDetectFaceAndHeadCoverings(people.isDetectFaceAndHeadCoverings());
        s.setEstimateAgeGroup(people.isEstimateAgeGroup());
        // Every setLicensePlate* setter is deliberately untouched - the plate reader is kept
        // out by never putting LICENSE_PLATE_RECOGNITION in the source's modality set.
        // Deliberately NOT calling setFacesWithDetailsOnly(): the Java binding still has the
        // method, but SentiVeillance 10.1's libNSurveillance.so does not expose the
        // 'Faces.WithDetailsOnly' property behind it, so the call throws
        // NIllegalArgumentException("Property with the specified name not found"). The SDK's
        // own samples never set it either. 'Faces.ConfidenceThreshold' is the supported knob
        // in this area if detection strictness ever needs tuning.

        log.info("Engine settings - gpu={}, matchingThreshold={}, quality>={}, interOcular>={}px, template={}",
                properties.getSdk().isUseGpu(),
                faces.getMatchingThreshold(),
                faces.getQualityThreshold(),
                faces.getMinimalInterOcularDistance(),
                faces.getTemplateSize());

        log.info("People settings  - humansOnly={}, detectorScaling={}, bodyThreshold={}, clothing={}, "
                        + "headCoverings={}, ageGroup={}",
                people.isHumansOnly(),
                people.getDetectorScaling(),
                people.getDetectorThreshold(),
                people.isDetectClothingDetails(),
                people.isDetectFaceAndHeadCoverings(),
                people.isEstimateAgeGroup());
    }

    /**
     * Caps how often the detector is fed a frame, which is what keeps the overlay live.
     *
     * <h2>Measured effect</h2>
     * Peak {@code detectionLagMillis} over the same clip, at {@code detectorScaling=ONE}:
     * <pre>
     *   0.17s (the SDK default)  ~4900 ms   ~117 events, both people recognised
     *   0.33s                    ~2270 ms    ~71 events, both people recognised
     *   0.50s                     ~810 ms    ~45 events, both people recognised
     * </pre>
     * The cost is temporal resolution - fewer samples per visit, so a fast-moving person is
     * tracked more coarsely - not accuracy: identification still worked at every setting.
     */
    private void applyDetectorFrameRateCap(NSurveillance s, People people) {
        float seconds = people.getDetectorMinFrameTimeSeconds();
        if (seconds <= 0) {
            log.info("Detector frame-rate cap disabled - the SDK default applies");
            return;
        }

        try {
            s.setProperty("ObjectTracker.BatcherMinFrameTime", seconds);
            log.info("Detector frame-rate cap: {}s between frames (~{} fps)",
                    seconds, Math.round(1 / seconds));
        } catch (Throwable t) {
            log.warn("Could not set ObjectTracker.BatcherMinFrameTime={} - the SDK default stays "
                    + "in place and the overlay will lag further behind: {}", seconds, t.toString());
        }
    }

    private void loadWatchList(Collection<Employee> watchList) {
        int added = 0;

        for (Employee employee : watchList) {
            if (employee.getFaceTemplate() == null || employee.getFaceTemplate().length == 0) {
                continue;
            }
            surveillance.addTemplate(employee.getTemplateId(), employee.getFaceTemplate());
            watchListNames.put(employee.getTemplateId(), employee.getFullName());
            added++;
        }

        watchListSize.set(added);
        log.info("Watch list loaded: {} template(s)", added);
    }

    private void subscribe() {
        surveillance.addRunningChangeListener(onRunningChanged);
        surveillance.addImageCapturedListener(liveFrames.listener());
        surveillance.addSubjectAppearedListener(onSubjectAppeared);
        surveillance.addSubjectTrackListener(onSubjectTrack);
        surveillance.addSubjectDisappearedListener(onSubjectDisappeared);
    }

    private void unsubscribe() {
        surveillance.removeImageCapturedListener(liveFrames.listener());
        liveFrames.clear();
        recognizedPeople.clear();
        surveillance.removeRunningChangeListener(onRunningChanged);
        surveillance.removeSubjectAppearedListener(onSubjectAppeared);
        surveillance.removeSubjectTrackListener(onSubjectTrack);
        surveillance.removeSubjectDisappearedListener(onSubjectDisappeared);
    }

    /** Hands the camera back to the device manager that produced it. */
    private void disconnectCamera(NSurveillanceSource s) {
        try {
            NCamera camera = s.getCamera();
            if (camera == null || !camera.isDisconnectable()) {
                return;
            }

            surveillance.getDeviceManager().disconnectFromDevice(camera);
            log.info("Camera disconnected from the device manager");
        } catch (Throwable t) {
            log.warn("Could not disconnect the camera: {}", t.toString());
        }
    }

    private void requireStarted() {
        if (surveillance == null || source == null) {
            throw new IllegalStateException("Surveillance engine is not started");
        }
    }

    // ------------------------------------------------------------------
    // event handling
    // ------------------------------------------------------------------

    /**
     * Runs on an SDK callback thread. Copies each details object into a record, disposes the
     * native handle, and hands the records to the writer thread. Keep this short.
     */
    private void handle(NSurveillanceEvent event, String eventTypeName) {
        NSurveillanceEventDetails[] detailsArray = event.getEventDetailsArray();
        boolean disappeared = NSurveillanceEventType.SUBJECT_DISAPPEARED.name().equals(eventTypeName);

        for (NSurveillanceEventDetails details : detailsArray) {
            SubjectEventSnapshot snapshot;
            try {
                snapshot = copy(details, eventTypeName, disappeared);
            } catch (Throwable t) {
                log.error("Failed to read event details: {}", t.toString());
                continue;
            } finally {
                details.dispose();
            }

            if (!isHumanSubject(snapshot)) {
                // A vehicle the shared body detector happened to classify. Dropped here so it
                // never reaches the counters, the database or the dashboard.
                nonHumanIgnored.incrementAndGet();
                continue;
            }

            // Push to the dashboard first. The overlay used to be published from
            // DetectionRecorder after the INSERT, which put the database on the critical path
            // for something purely visual (measured: median 14 ms, worst 131 ms of queue wait
            // behind the single writer thread). Nothing here touches JDBC.
            try {
                liveEvents.publish(snapshot, snapshot.matchedTemplateId() == null
                        ? null : watchListNames.get(snapshot.matchedTemplateId()));
            } catch (Exception e) {
                log.debug("Could not push event to the dashboard: {}", e.toString());
            }
            // Tell the preview encoder where this subject is, so the next frame carries the
            // box. Burned into the image server-side rather than drawn by the page - see
            // LiveFrameService.render.
            if (disappeared) {
                liveFrames.hideBox(snapshot.traceIndex());
            } else if (snapshot.rectLeft() != null) {
                String identity = snapshot.matchedTemplateId() == null
                        ? identityByTrace.get(snapshot.traceIndex())
                        : snapshot.matchedTemplateId();
                String name = identity == null ? null : watchListNames.getOrDefault(identity, identity);
                String label = name == null
                        ? "unknown"
                        : name + (snapshot.matchScore() == null ? "" : " " + snapshot.matchScore());
                liveFrames.showBox(snapshot.traceIndex(),
                        snapshot.rectLeft(), snapshot.rectTop(),
                        snapshot.rectWidth(), snapshot.rectHeight(),
                        label, name != null);
            }

            if (snapshot.eventTime() != null) {
                detectionLagMillis.set(Math.max(0,
                        java.time.Duration.between(snapshot.eventTime(), Instant.now()).toMillis()));
            }

            eventsSeen.incrementAndGet();
            if (snapshot.isIdentified()) {
                identityByTrace.put(snapshot.traceIndex(), snapshot.matchedTemplateId());
                CandidateCapture capture = candidateCaptures.get(snapshot.traceIndex());

                if (capture != null && !capture.identified) {
                    capture.identified = true;
                    candidateExecutor.execute(() -> {
                        if (capture.savedId != null) unknownCandidates.removeIfPending(capture.savedId);
                    });
                }
            }

            if (disappeared) {
                candidateCaptures.remove(snapshot.traceIndex());
                subjectsSeen.incrementAndGet();
                // Whoever this visit turned out to be, from the whole track rather than this
                // one event.
                if (identityByTrace.remove(snapshot.traceIndex()) != null) {
                    identifiedSeen.incrementAndGet();
                }
            }

            eventExecutor.execute(() -> {
                try {
                    recorder.record(snapshot);
                } catch (Throwable t) {
                    log.error("Failed to store event: {}", t.toString(), t);
                }
            });
        }
    }

    /** Object types the shared body detector emits that are not a person. */
    private static final java.util.Set<String> NON_HUMAN_OBJECT_TYPES =
            java.util.Set.of("CAR", "BUS", "TRUCK", "BIKE");

    /** Whether this event describes a person. */
    private boolean isHumanSubject(SubjectEventSnapshot snapshot) {
        if (!properties.getPeople().isHumansOnly()) {
            return true;
        }

        String objectType = snapshot.objectType();
        return objectType == null || !NON_HUMAN_OBJECT_TYPES.contains(objectType);
    }

    /** Pulls everything we care about out of a native details object. */
    private SubjectEventSnapshot copy(NSurveillanceEventDetails details, String eventTypeName, boolean disappeared) {
        String sourceName = properties.getSource().getName();
        Throwable error = details.getError();

        if (error != null) {
            return new SubjectEventSnapshot(sourceName, eventTypeName, details.getTraceIndex(),
                    Instant.now(), 0L, null, null, null, false, null, null,
                    null, null, null, null, null, error.toString());
        }

        EnumSet<?> subjectTypes = details.getSubjectType();
        NSurveillanceObjectTypeDetails objectType = details.getObjectType();

        String matchedId = null;
        Integer matchScore = null;
        // BestMatches is ordered best-first and already filtered by matchingThreshold.
        for (NSEDMatchResult match : details.getBestMatches()) {
            matchedId = match.getId();
            matchScore = match.getScore();
            break;
        }

        Instant timestamp = details.getTimeStamp() != null
                ? details.getTimeStamp().toInstant()
                : Instant.now();
        long frameIndex = details.getFrameIndex();

        Integer left = null, top = null, width = null, height = null;
        boolean hasFaceDetails = false;
        String snapshotPath = null;

        NSurveillanceEventDetails best = details.getBestDetails();
        if (best != null) {
            try {
                NRect rect = best.getRectangle();
                if (rect != null) {
                    left = rect.left;
                    top = rect.top;
                    width = rect.width();
                    height = rect.height();
                }

                hasFaceDetails = best.getAttributesContainsDetails();
                String identity = matchedId == null ? identityByTrace.get(details.getTraceIndex()) : matchedId;

                if (identity != null) {
                    byte[] face = null;
                    if (hasFaceDetails && recognizedPeople.needsFace(identity)) {
                        face = recognizedFace(best);
                    }

                    recognizedPeople.seen(identity, watchListNames.getOrDefault(identity, identity),
                            sourceName, matchScore, face);
                }

                if (disappeared && properties.getSnapshots().isEnabled()) {
                    snapshotPath = saveSnapshot(best, details.getTraceIndex(), matchedId, rect,
                            hasFaceDetails);
                }

                if (hasFaceDetails && identity == null
                        && properties.getEnrollment().isCaptureUnknownSubjects()
                        && (objectType == null || objectType.Type == null
                            || !NON_HUMAN_OBJECT_TYPES.contains(objectType.Type.name()))) {
                    queueCandidate(details, best, sourceName, disappeared);
                }
            } finally {
                best.dispose();
            }
        }

        return new SubjectEventSnapshot(
                sourceName,
                eventTypeName,
                details.getTraceIndex(),
                timestamp,
                frameIndex,
                subjectTypes == null ? null : subjectTypes.toString(),
                objectType == null || objectType.Type == null ? null : objectType.Type.name(),
                details.getDetectionConfidence(),
                hasFaceDetails,
                matchedId,
                matchScore,
                left, top, width, height,
                snapshotPath,
                null);
    }

    private void queueCandidate(NSurveillanceEventDetails details, NSurveillanceEventDetails best,
                                String sourceName, boolean disappeared) {
        int trace = details.getTraceIndex();
        CandidateCapture capture = candidateCaptures.computeIfAbsent(trace, ignored -> new CandidateCapture());

        if (capture.identified || capture.suppressed || (capture.pending && !disappeared)
                || (!disappeared && System.currentTimeMillis() < capture.nextAttempt)) return;
        com.neurotec.biometrics.NLAttributes attributes = null;

        try {
            attributes = best.getAttributes(false);

            if (attributes == null) return;
            int rawQuality = Byte.toUnsignedInt(attributes.getQuality());
            int quality = rawQuality <= 100 ? rawQuality : -1;
            long frame = best.getFrameIndex();
            // Never spend CPU on equal/worse frames after a successful capture.
            if (capture.savedId != null && quality <= capture.savedQuality) return;
            if (!disappeared && frame == capture.attemptedFrame) return;
            // A finished visit needs one final fallback even if an early extraction is
            // still queued. It runs behind that attempt and cannot create a second card.
            boolean usesSlot = !disappeared;

            if (usesSlot && !candidateSlots.tryAcquire()) return;

            boolean submitted = false;
            try {
                byte[] jpeg = recognizedFace(best);

                if (jpeg == null) return;
                // Some SDK events already contain extracted features. Copy them while their
                // native handles are valid, avoiding another detect/extract pass entirely.
                byte[] template = liveCandidateTemplate(details, attributes);
                capture.pending = true;
                capture.attemptedFrame = frame;
                capture.nextAttempt = System.currentTimeMillis() + 250;
                candidateExecutor.execute(() -> {
                    try { captureCandidate(sourceName, trace, jpeg, template, quality, capture); }
                    finally { if (usesSlot) candidateSlots.release(); }
                });
                submitted = true;
            } finally {
                if (!submitted) {
                    capture.pending = false;
                    if (usesSlot) candidateSlots.release();
                }
            }
        } catch (Exception e) {
            log.debug("Could not copy unknown face for trace {}: {}", trace, e.toString());
        } finally {
            if (attributes != null) attributes.dispose();
        }
    }

    private byte[] liveCandidateTemplate(NSurveillanceEventDetails details,
                                         com.neurotec.biometrics.NLAttributes attributes) {
        com.neurotec.biometrics.NLTemplate template = null;
        try {
            template = attributes.getTemplate(false);
            if (template == null) template = details.getSubjectTemplate();
            if (template == null) return null;
            try (var buffer = template.save()) { return buffer.toByteArray(); }
        } catch (Exception e) {
            // SDK versions that only expose templates at disappearance use the warm extractor.
            return null;
        } finally {
            if (template != null) template.dispose();
        }
    }

    void captureCandidate(String sourceName, int trace, byte[] jpeg, byte[] liveTemplate,
                          int quality, CandidateCapture capture) {
        try {
            if (capture.identified || capture.suppressed) return;
            byte[] bytes = liveTemplate != null && liveTemplate.length > 0
                    ? liveTemplate : candidateMatcher.extract(jpeg);

            if (bytes == null || bytes.length == 0 || capture.identified) return;
            var result = unknownCandidates.capture(sourceName, trace, capture.savedId, bytes, jpeg, quality);

            if (result == null) {
                capture.suppressed = true; // dismissed/enrolled while this frame was in flight
                return;
            }
            capture.savedId = result.id();
            capture.savedQuality = result.quality();

            if (result.created()) unknownCaptured.incrementAndGet();
        } catch (Exception e) {
            log.warn("Could not queue unknown face for trace {} (will retry): {}", trace, e.toString());
        } finally {
            capture.pending = false;
        }
    }

    /** Captures the best frame of a finished track for eyeballing results. */
    private String saveSnapshot(NSurveillanceEventDetails best, int traceIndex, String matchedId,
                                NRect rect, boolean hasFaceDetails) {
        NImage image = null;
        NImage cropped = null;

        try {
            // false = the SDK keeps ownership of the pixel buffer, same as its own samples.
            image = best.getOriginalImage(false);

            if (image == null) {
                return null;
            }
            // Crop to the person. A full 2160x3840 frame with several people in it is useless
            // for deciding who you are about to enrol. The face rectangle is preferred over the
            // detection rectangle: the latter is a whole body, which for somebody standing close
            // is most of the frame again and leaves their face a small part of a large image.
            cropped = cropToFace(image, best, hasFaceDetails);

            if (cropped == null) {
                cropped = cropToSubject(image, rect);
            }

            if (cropped != null) {
                image = cropped;
            }
            Path dir = Path.of(properties.getSnapshots().getDirectory()).toAbsolutePath().normalize();
            String name = String.format("%s_trace%03d_%s.jpg",
                    Instant.now().toEpochMilli(),
                    traceIndex,
                    matchedId == null ? "unknown" : matchedId);
            Path target = dir.resolve(name);

            com.neurotec.io.NBuffer encoded = image.save(NImageFormat.getJPEG());

            byte[] jpeg;
            try {
                jpeg = encoded.toByteArray();
            } finally {
                encoded.dispose();
            }

            snapshotExecutor.execute(() -> {
                try {
                    Files.createDirectories(dir);
                    Files.write(target, jpeg);
                } catch (Exception e) {
                    log.warn("Could not write snapshot {}: {}", target, e.toString());
                }
            });
            return target.toString();
        } catch (Exception e) {
            log.warn("Could not save snapshot for trace {}: {}", traceIndex, e.toString());
            return null;
        } finally {
            // Must be released here. getOriginalImage(false) hands back a wrapper around a
            // buffer the SDK still owns, and the caller disposes the parent details object
            // immediately after this returns. Leaving the wrapper alive means its native
            // handle is freed later, by the cleaner, against memory that is already gone -
            // which is what aborted the JVM at exit with "corrupted double-linked list".
            // The SDK's own web sample disposes it the same way, in EventData.dispose().
            if (image != null) {
                image.dispose();
            }

            if (cropped != null && cropped != image) {
                cropped.dispose();
            }
        }
    }

    /** Encode only a face crop, at most once per person every five seconds. */
    private byte[] recognizedFace(NSurveillanceEventDetails best) {
        NImage original = null;
        NImage face = null;

        try {
            original = best.getOriginalImage(false);
            if (original == null) return null;

            face = cropToFace(original, best, true);
            if (face == null) return null;

            var encoded = face.save(NImageFormat.getJPEG());
            try {
                return encoded.toByteArray();
            } finally {
                encoded.dispose();
            }
        } catch (Exception e) {
            log.debug("Could not capture recognized face: {}", e.toString());
            return null;
        } finally {
            if (face != null) face.dispose();
            if (original != null) original.dispose();
        }
    }

    /** Cuts out the face, using the geometry the face detector itself reported. */
    private NImage cropToFace(NImage image, NSurveillanceEventDetails best, boolean hasFaceDetails) {
        if (!hasFaceDetails) {
            return null;
        }

        com.neurotec.biometrics.NLAttributes attributes = null;
        try {
            attributes = best.getAttributes(false);
            if (attributes == null) {
                return null;
            }

            NRect face = attributes.getBoundingRect();
            if (face == null || face.width() <= 0 || face.height() <= 0) {
                return null;
            }

            double padding = properties.getSnapshots().getFacePadding();
            int padX = (int) Math.round(face.width() * padding);
            int padY = (int) Math.round(face.height() * padding);
            int left = Math.max(0, face.left - padX);
            int top = Math.max(0, face.top - padY);
            int right = Math.min(image.getWidth(), face.left + face.width() + padX);
            int bottom = Math.min(image.getHeight(), face.top + face.height() + padY);

            if (right - left <= 0 || bottom - top <= 0) {
                return null;
            }
            return image.crop(left, top, right - left, bottom - top);
        } catch (Throwable t) {
            log.debug("No face geometry to crop: {}", t.toString());
            return null;
        } finally {
            if (attributes != null) {
                attributes.dispose();
            }
        }
    }

    /** Cuts the subject out of the frame, with some room around them for context. */
    private NImage cropToSubject(NImage image, NRect rect) {
        if (rect == null) {
            return null;
        }

        try {
            double padding = properties.getSnapshots().getSubjectPadding();
            int w = rect.width();
            int h = rect.height();

            if (w <= 0 || h <= 0) {
                return null;
            }

            int padX = (int) Math.round(w * padding);
            int padY = (int) Math.round(h * padding);

            int left = Math.max(0, rect.left - padX);
            int top = Math.max(0, rect.top - padY);
            int right = Math.min(image.getWidth(), rect.left + w + padX);
            int bottom = Math.min(image.getHeight(), rect.top + h + padY);

            if (right - left <= 0 || bottom - top <= 0) {
                return null;
            }
            return image.crop(left, top, right - left, bottom - top);
        } catch (Throwable t) {
            log.debug("Could not crop the snapshot for trace: {}", t.toString());
            return null;
        }
    }
}
