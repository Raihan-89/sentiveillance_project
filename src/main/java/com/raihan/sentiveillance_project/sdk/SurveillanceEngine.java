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
import com.raihan.sentiveillance_project.detection.SubjectEventSnapshot;
import com.raihan.sentiveillance_project.domain.Employee;
import com.raihan.sentiveillance_project.domain.UnknownSubject;
import com.raihan.sentiveillance_project.domain.UnknownSubjectRepository;
import com.raihan.sentiveillance_project.live.LiveEventStream;
import com.raihan.sentiveillance_project.live.LiveFrameService;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
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
 * <h2>What the SDK objects do</h2>
 * <ul>
 *   <li>{@code NSurveillance} is the engine. It holds the loaded neural-network models, the
 *       watch list of face templates, the detection thresholds, and the worker threads.
 *       Creating it is expensive (models are read from {@code Bin/Data}), so there is exactly
 *       one per process - and it is why 20-30 cameras later will still share a single engine.</li>
 *   <li>{@code NSurveillance.Sources} is the list of inputs. Each {@link NSurveillanceSource}
 *       wraps one camera/file and carries its own modality set, region of interest and
 *       search areas. Sources start and stop independently of each other.</li>
 *   <li><b>Templates</b> are added with {@code addTemplate(id, bytes)}. The engine matches
 *       every tracked face against all of them continuously and reports the best hits.</li>
 *   <li><b>Events</b> are callbacks, not polling. The interesting ones are
 *       {@code SUBJECT_APPEARED} (a new person entered the frame),
 *       {@code SUBJECT_TRACK} (a periodic update as they move, where recognition scores
 *       accumulate and improve), and {@code SUBJECT_DISAPPEARED} (they left - this carries
 *       the best face template collected across the whole visit). All three share a
 *       {@code traceIndex}, which is the SDK's identity for one visit.</li>
 *   <li><b>Thresholds</b> decide what gets reported: {@code matchingThreshold} is the score
 *       a face must reach to be called a known employee, while quality and inter-ocular
 *       distance decide whether a frame is even worth extracting a template from.</li>
 * </ul>
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
    /** Review queue of people the engine saw but could not name. */
    private final UnknownSubjectRepository unknownSubjects;

    /**
     * Single-threaded so database rows land in event order; separate from the SDK's threads
     * so a slow write never blocks frame processing.
     */
    private final ExecutorService eventExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "detection-writer"));
    /**
     * Snapshot JPEGs are written here, not on the SDK callback thread. Measured: writing one
     * 390 KB frame inline cost 64-71 ms of the capture thread per disappeared subject, which
     * is back-pressure straight into the video pipeline.
     */
    private final ExecutorService snapshotExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "snapshot-writer"));

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
     * traceIndex -> the watch-list id matched at any point during that visit.
     *
     * <p>Needed because recognition and the end of a track are reported separately: matches
     * arrive on SUBJECT_TRACK events, while SUBJECT_DISAPPEARED carries no {@code bestMatches}
     * at all. Judging identity from the disappear event alone therefore calls every visit
     * unidentified - which both left {@code identified} stuck at zero and queued known people
     * for naming. The entry is removed when the track ends.
     */
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
        log.info("Surveillance running = {}", running);
    };

    public SurveillanceEngine(SentiveillanceProperties properties,
                              LicenseService licenseService,
                              SurveillanceSourceFactory sourceFactory,
                              DetectionRecorder recorder,
                              LiveFrameService liveFrames,
                              LiveEventStream liveEvents,
                              UnknownSubjectRepository unknownSubjects) {
        this.properties = properties;
        this.licenseService = licenseService;
        this.sourceFactory = sourceFactory;
        this.recorder = recorder;
        this.liveFrames = liveFrames;
        this.liveEvents = liveEvents;
        this.unknownSubjects = unknownSubjects;
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
        log.info("Watch list now holds {} template(s)", surveillance.getTemplateIds().length);
    }

    /** Records the display name for a live-enrolled person, so the push can label them. */
    public void rememberWatchListName(String templateId, String fullName) {
        watchListNames.put(templateId, fullName);
    }

    /** Starts the configured source if it is not already capturing. */
    public synchronized void startSource() {
        requireStarted();
        surveillance.startSource(source);
    }

    public synchronized void stopSource() {
        requireStarted();
        surveillance.stopSource(source);
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
            try {
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
                surveillance == null ? 0 : surveillance.getTemplateIds().length,
                nonHumanIgnored.get(), detectionLagMillis.get(), unknownCaptured.get());
    }

    /**
     * @author Md. Raihan Shikder (Raihan-89)
     * @date 8 November, 2026
     */
    public record Stats(boolean running, long events, long subjects, long identified, int watchListSize,
                        long nonHumanIgnored, long detectionLagMillis, long unknownCaptured) {
    }

    // ------------------------------------------------------------------
    // setup
    // ------------------------------------------------------------------

    private void applySettings(NSurveillance s) {
        SentiveillanceProperties.Faces faces = properties.getFaces();

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
        SentiveillanceProperties.People people = properties.getPeople();
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
     * <h2>The problem this solves</h2>
     * Measured on this machine: the detector processed a 7.5s, 2160x3840, 30 fps clip in
     * 13.2s of wall time - about 0.57x real time. A source that produces frames faster than
     * the detector consumes them builds a backlog, so the gap between what the camera sees
     * and what the overlay shows does not settle, it grows for as long as the source runs.
     * No amount of transport tuning fixes that; the detector has to be given less work.
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
     *
     * <h2>Why setProperty</h2>
     * {@code ObjectTracker.BatcherMinFrameTime} has no setter in the Java binding, so it is
     * set through the generic property API. It is present in SentiVeillance 10.1 (read back
     * as a Float, default 0.17, in seconds) but it is not part of the documented surface, so
     * a failure here is logged and ignored rather than being allowed to stop startup - the
     * same lesson as {@code Faces.WithDetailsOnly}, which the binding exposes and the native
     * library does not.
     */
    private void applyDetectorFrameRateCap(NSurveillance s, SentiveillanceProperties.People people) {
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
        surveillance.removeRunningChangeListener(onRunningChanged);
        surveillance.removeSubjectAppearedListener(onSubjectAppeared);
        surveillance.removeSubjectTrackListener(onSubjectTrack);
        surveillance.removeSubjectDisappearedListener(onSubjectDisappeared);
    }

    /**
     * Hands the camera back to the device manager that produced it.
     *
     * <p>{@link SurveillanceSourceFactory} obtains it through
     * {@code NDeviceManager.connectToDevice}, so something has to disconnect it again.
     * Skipping this leaves the NdmMedia plugin holding a live device while the engine is
     * disposed, and the process then dies during native teardown - either
     * {@code NError (-7) NdmMedia is not initialized} or a glibc abort
     * ("corrupted double-linked list", exit 134) after all Java shutdown logging is done.
     *
     * <p>The {@code isDisconnectable()} guard and the ordering are taken from the SDK's own
     * {@code SurveillanceService.remove(SourceState)} in the Java web sample.
     */
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
                liveEvents.publish(snapshot, watchListNames.get(snapshot.matchedTemplateId()));
            } catch (Exception e) {
                log.debug("Could not push event to the dashboard: {}", e.toString());
            }
            if (snapshot.eventTime() != null) {
                detectionLagMillis.set(Math.max(0,
                        java.time.Duration.between(snapshot.eventTime(), Instant.now()).toMillis()));
            }

            eventsSeen.incrementAndGet();
            if (snapshot.isIdentified()) {
                identityByTrace.put(snapshot.traceIndex(), snapshot.matchedTemplateId());
            }
            if (disappeared) {
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

    /**
     * Whether this event describes a person.
     *
     * <p>The SDK's body detector cannot be restricted to people: it classifies every box it
     * finds as PERSON, CAR, BUS, TRUCK or BIKE, and the modality that enables it is literally
     * called {@code VEHICLES_AND_HUMANS}. Filtering here is therefore the only way to get a
     * people-only pipeline while still tracking somebody whose face is turned away.
     *
     * <p>A null object type is kept: face events carry no object type at all, and an error
     * event has to be reported whatever it was about.
     */
    private boolean isHumanSubject(SubjectEventSnapshot snapshot) {
        if (!properties.getPeople().isHumansOnly()) {
            return true;
        }
        String objectType = snapshot.objectType();
        return objectType == null || !NON_HUMAN_OBJECT_TYPES.contains(objectType);
    }

    /**
     * Pulls everything we care about out of a native details object.
     *
     * <p>{@code getBestDetails()} returns the single best frame the engine saw for this
     * subject across the whole track - that is the one worth saving a picture of, not the
     * current frame. Mirrors the SDK's {@code EventData} constructor in the Java web sample.
     */
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
                if (disappeared && properties.getSnapshots().isEnabled()) {
                    snapshotPath = saveSnapshot(best, details.getTraceIndex(), matchedId);
                }
                // Only queue people who were never recognised during the entire visit.
                if (disappeared && hasFaceDetails
                        && matchedId == null
                        && !identityByTrace.containsKey(details.getTraceIndex())) {
                    captureUnknownSubject(details, snapshotPath);
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

    private void captureUnknownSubject(NSurveillanceEventDetails details, String snapshotPath) {
        if (!properties.getEnrollment().isCaptureUnknownSubjects()) {
            return;
        }
        com.neurotec.biometrics.NLTemplate template = null;
        try {
            template = details.getSubjectTemplate();
            if (template == null) {
                return;
            }
            com.neurotec.io.NBuffer buffer = template.save();
            byte[] bytes;
            try {
                bytes = buffer.toByteArray();
            } finally {
                buffer.dispose();
            }
            if (bytes.length == 0) {
                return;
            }

            UnknownSubject subject = new UnknownSubject();
            subject.setSourceName(properties.getSource().getName());
            subject.setTraceIndex(details.getTraceIndex());
            subject.setFaceTemplate(bytes);
            subject.setSnapshotName(snapshotPath == null
                    ? null
                    : Path.of(snapshotPath).getFileName().toString());

            eventExecutor.execute(() -> {
                try {
                    unknownSubjects.save(subject);
                    unknownCaptured.incrementAndGet();
                } catch (Throwable t) {
                    log.warn("Could not store an unknown subject for review: {}", t.toString());
                }
            });
        } catch (Throwable t) {
            log.debug("No usable template for unknown trace {}: {}", details.getTraceIndex(), t.toString());
        } finally {
            if (template != null) {
                template.dispose();
            }
        }
    }

    /**
     * Captures the best frame of a finished track for eyeballing results.
     *
     * <p>Runs on the SDK callback thread, so it does the minimum that cannot be deferred:
     * {@code best} is a native handle the caller disposes the moment this returns, so the
     * pixels have to be encoded now. The resulting bytes are then handed to
     * {@link #snapshotExecutor} and the file is written there - the disk write was the
     * expensive half, and doing both inline cost 64-71 ms of the capture thread per subject.
     *
     * @return where the file will be written, or null if there was nothing to capture
     */
    private String saveSnapshot(NSurveillanceEventDetails best, int traceIndex, String matchedId) {
        NImage image = null;
        try {
            // false = the SDK keeps ownership of the pixel buffer, same as its own samples.
            image = best.getOriginalImage(false);
            if (image == null) {
                return null;
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
        }
    }
}
