package com.raihan.sentiveillance_project.sdk;

import com.neurotec.biometrics.NTemplateSize;
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

    /**
     * Single-threaded so database rows land in event order; separate from the SDK's threads
     * so a slow write never blocks frame processing.
     */
    private final ExecutorService eventExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "detection-writer"));

    private NSurveillance surveillance;
    private NSurveillanceSource source;

    @Getter
    private volatile boolean running;
    private final AtomicLong eventsSeen = new AtomicLong();
    private final AtomicLong subjectsSeen = new AtomicLong();
    private final AtomicLong identifiedSeen = new AtomicLong();

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
                              DetectionRecorder recorder) {
        this.properties = properties;
        this.licenseService = licenseService;
        this.sourceFactory = sourceFactory;
        this.recorder = recorder;
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
        applySettings(surveillance);
        loadWatchList(watchList);

        source = sourceFactory.create(surveillance);
        surveillance.getSources().add(source);

        subscribe();

        if (properties.getSource().isAutoStart()) {
            log.info("Starting source '{}' ...", properties.getSource().getName());
            // startSource starts capture for this one source. surveillance.start() would
            // start every source at once - the per-source call is what scales to many cameras.
            surveillance.startSource(source);
        } else {
            log.info("autoStart is false - call POST /api/surveillance/start to begin");
        }
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
            unsubscribe();
            if (source != null) {
                surveillance.stopSource(source);
                surveillance.getSources().remove(source);
            }
            surveillance.stop();
        } catch (Exception e) {
            log.warn("Error while stopping surveillance: {}", e.toString());
        } finally {
            eventExecutor.shutdown();
            try {
                eventExecutor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            surveillance.dispose();
            surveillance = null;
            source = null;
            running = false;
            log.info("Totals - events: {}, subjects: {}, identified: {}",
                    eventsSeen.get(), subjectsSeen.get(), identifiedSeen.get());
        }
    }

    public Stats stats() {
        return new Stats(running, eventsSeen.get(), subjectsSeen.get(), identifiedSeen.get(),
                surveillance == null ? 0 : surveillance.getTemplateIds().length);
    }

    /**
     * @author Md. Raihan Shikder (Raihan-89)
     * @date 8 November, 2026
     */
    public record Stats(boolean running, long events, long subjects, long identified, int watchListSize) {
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
        s.setFacesWithDetailsOnly(faces.isWithDetailsOnly());

        log.info("Engine settings - gpu={}, matchingThreshold={}, quality>={}, interOcular>={}px, template={}",
                properties.getSdk().isUseGpu(),
                faces.getMatchingThreshold(),
                faces.getQualityThreshold(),
                faces.getMinimalInterOcularDistance(),
                faces.getTemplateSize());
    }

    private void loadWatchList(Collection<Employee> watchList) {
        int added = 0;
        for (Employee employee : watchList) {
            if (employee.getFaceTemplate() == null || employee.getFaceTemplate().length == 0) {
                continue;
            }
            surveillance.addTemplate(employee.getTemplateId(), employee.getFaceTemplate());
            added++;
        }
        log.info("Watch list loaded: {} template(s)", added);
    }

    private void subscribe() {
        surveillance.addRunningChangeListener(onRunningChanged);
        surveillance.addSubjectAppearedListener(onSubjectAppeared);
        surveillance.addSubjectTrackListener(onSubjectTrack);
        surveillance.addSubjectDisappearedListener(onSubjectDisappeared);
    }

    private void unsubscribe() {
        surveillance.removeRunningChangeListener(onRunningChanged);
        surveillance.removeSubjectAppearedListener(onSubjectAppeared);
        surveillance.removeSubjectTrackListener(onSubjectTrack);
        surveillance.removeSubjectDisappearedListener(onSubjectDisappeared);
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

            eventsSeen.incrementAndGet();
            if (disappeared) {
                subjectsSeen.incrementAndGet();
                if (snapshot.isIdentified()) {
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

    /** Writes the best frame of a finished track to disk, for eyeballing results. */
    private String saveSnapshot(NSurveillanceEventDetails best, int traceIndex, String matchedId) {
        NImage image = null;
        try {
            // false = the SDK keeps ownership of the pixel buffer, same as its own samples.
            image = best.getOriginalImage(false);
            if (image == null) {
                return null;
            }
            Path dir = Path.of(properties.getSnapshots().getDirectory()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String name = String.format("%s_trace%03d_%s.jpg",
                    Instant.now().toEpochMilli(),
                    traceIndex,
                    matchedId == null ? "unknown" : matchedId);
            Path target = dir.resolve(name);
            image.save(target.toString(), NImageFormat.getJPEG());
            return target.toString();
        } catch (Exception e) {
            log.warn("Could not save snapshot for trace {}: {}", traceIndex, e.toString());
            return null;
        }
    }
}
