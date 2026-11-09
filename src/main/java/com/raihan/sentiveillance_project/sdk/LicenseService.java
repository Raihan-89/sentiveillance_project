package com.raihan.sentiveillance_project.sdk;

import com.neurotec.licensing.NLicense;
import com.neurotec.licensing.NLicenseManager;
import com.neurotec.licensing.NLicenseObtainedStatus;
import com.neurotec.surveillance.NSurveillanceModalityType;
import com.raihan.sentiveillance_project.config.SentiveillanceProperties;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.EnumSet;

/**
 * Obtains SDK licenses and works out which detectors we are actually allowed to run.
 *
 * <p>Nothing in the SDK works before this succeeds: {@code new NSurveillance()} itself
 * throws if no license has been obtained. {@code NLicense.obtain("/local", 5000, component)}
 * talks to the {@code pgd} licensing daemon over TCP - if pgd is not running you get an
 * IOException, not a licensing error.
 *
 * <p>Component names and the obtain/isComponentActivated split are copied from the SDK's
 * {@code Samples/Surveillance/Java/surveillance-web-sample/.../SurveillanceService.java}.
 *
 *  @author Md. Raihan Shikder (Raihan-89)
 *  @date 8 November, 2026
 */
@Slf4j
@Service
public class LicenseService {

    /** Face detection, tracking and recognition. */
    private static final String COMPONENT_FACES = "SentiVeillance";
    /**
     * The body detector, licensed by Neurotechnology as "VH" (vehicles & humans).
     *
     * <p>There is no humans-only component to buy and no humans-only modality to enable: this
     * is one neural detector that classifies every box it finds as PERSON, CAR, BUS, TRUCK or
     * BIKE. It is needed here because it is the only thing that tracks a person whose face is
     * turned away from the camera. The vehicle classes it also emits are discarded - see
     * {@code SurveillanceEngine.isHuman} - and vehicle attribute extraction is switched off.
     */
    private static final String COMPONENT_BODIES = "SentiVeillanceVH";

    /**
     * Exactly the two components this project uses.
     *
     * <p>{@code SentiVeillanceALPR} (licence-plate recognition) is deliberately absent: it is
     * not obtained, not activated and its modality is never enabled, so no plate is ever read.
     */
    private static final String[] COMPONENTS = {COMPONENT_FACES, COMPONENT_BODIES};

    private final SentiveillanceProperties properties;

    @Getter
    private boolean facesAvailable;
    /** Whether the body detector is licensed - people without a visible face. */
    @Getter
    private boolean bodiesAvailable;
    @Getter
    private boolean obtained;

    public LicenseService(SentiveillanceProperties properties) {
        this.properties = properties;
    }

    /**
     * @throws IllegalStateException when not a single component could be licensed
     */
    public void obtain() {
        if (obtained) {
            return;
        }
        SentiveillanceProperties.Sdk sdk = properties.getSdk();

        NLicenseManager.setTrialMode(sdk.isTrialMode());

        if (sdk.getUseHttps() != null) {
            NLicenseManager.setUseHttps(sdk.getUseHttps());
        }
        if (sdk.getLicenseStoragePath() != null && !sdk.getLicenseStoragePath().isBlank()) {
            NLicenseManager.setWritableStoragePathN(sdk.getLicenseStoragePath());
        }

        logLicensingEnvironment();

        boolean any = false;
        for (String component : COMPONENTS) {
            try {
                boolean ok = NLicense.obtain(sdk.getLicenseServer(), sdk.getLicensePort(), component);
                log.info("License {} -> {}", component, ok ? "obtained" : "NOT available");
                any |= ok;
            } catch (IOException e) {
                // Thrown when the daemon is unreachable, which is the usual first-run problem.
                throw new IllegalStateException(
                        "Could not reach the licensing service at " + sdk.getLicenseServer() + ":" + sdk.getLicensePort()
                                + ". Start it with Bin/Linux_x86_64/Activation/run_pgd.sh start", e);
            }
        }
        if (!any) {
            reportObtainedStatus(sdk);
            // obtain() returning false (rather than throwing) means the licensing service
            // answered and had nothing to give. Setting trialMode/TrialFlag.txt only tells
            // the SDK to *use* a trial licence - it does not create one. A trial is a
            // 30-day licence that must first be activated against Neurotechnology's
            // servers (Activation.pdf section 2.4).
            throw new IllegalStateException(String.join(System.lineSeparator(),
                    "No SentiVeillance licence component could be obtained.",
                    "",
                    "The licensing service answered but holds no licences. Check, in order:",
                    "  1. Is pgd running?   Bin/Linux_x86_64/Activation/run_pgd.sh log",
                    "  2. Does Bin/Licenses/ contain a .lic file? If it only has TrialFlag.txt,",
                    "     this SDK copy has never been activated - trial=true alone is not a licence.",
                    "  3. Does Activation/LicenceUsage.log list any product? Header-only means zero licences.",
                    "",
                    "To activate a 30-day trial or a purchased licence, see Documentation/Activation.pdf",
                    "and use Bin/Linux_x86_64/Activation/license_activation (a serial number is required",
                    "for purchased licences). Trial licences also need a constant internet connection."));
        }

        // Which detectors the obtained licenses actually unlock. Note the component names
        // used for the *activation check* differ from the ones used to obtain - that is how
        // the SDK's own sample does it.
        facesAvailable = isActivated("SentiVeillance");
        bodiesAvailable = isActivated("Sentiveillance.VH");
        log.info("Detectors available - faces: {}, bodies: {} (licence-plate recognition is never requested)",
                facesAvailable, bodiesAvailable);

        obtained = true;
    }

    /**
     * The modality set to put on the source - people and faces only.
     *
     * <p>{@code FACES} drives detection, tracking and watch-list recognition.
     * {@code VEHICLES_AND_HUMANS} is the body detector, so somebody is still tracked while
     * their face is turned away; its vehicle classes are filtered out of every event.
     *
     * <p>{@link NSurveillanceModalityType#LICENSE_PLATE_RECOGNITION} is never added. That is
     * what keeps the plate reader out of the pipeline entirely - the modality set on the
     * source is what decides which detectors the engine actually runs.
     *
     * @param wantBodyDetection whether to track people with no visible face
     */
    public EnumSet<NSurveillanceModalityType> resolveModalities(boolean wantBodyDetection) {
        EnumSet<NSurveillanceModalityType> modalities = EnumSet.noneOf(NSurveillanceModalityType.class);
        if (facesAvailable) {
            modalities.add(NSurveillanceModalityType.FACES);
        }
        if (wantBodyDetection && bodiesAvailable) {
            modalities.add(NSurveillanceModalityType.VEHICLES_AND_HUMANS);
        }
        if (modalities.isEmpty()) {
            throw new IllegalStateException("No usable modality: the face detector is not licensed");
        }
        return modalities;
    }

    /**
     * Logs the licensing client's own view of the world. Worth having in every run: a
     * trial licence is downloaded and cached under the writable storage path, so if that
     * path is wrong or unwritable, obtain fails with no useful message.
     */
    private void logLicensingEnvironment() {
        log.info("Trial mode              : {}", NLicenseManager.getTrialMode());
        try {
            log.info("Use HTTPS               : {}", NLicenseManager.getUseHttps());
        } catch (Exception e) {
            log.info("Use HTTPS               : <unavailable: {}>", e.toString());
        }
        try {
            String storage = NLicenseManager.getWritableStoragePath();
            log.info("Licence storage path    : {}", storage);
            if (storage != null && !storage.isBlank()) {
                java.io.File dir = new java.io.File(storage);
                log.info("  exists={} writable={}", dir.exists(), dir.canWrite());
            }
        } catch (Exception e) {
            log.info("Licence storage path    : <unavailable: {}>", e.toString());
        }
        // The licensing library resolves licence files against a "Licenses" directory in
        // the *application's working directory*, not the SDK folder - see Activation.pdf
        // section 4.1. Our working directory is the project root, so show what is there.
        java.io.File localLicenses = new java.io.File("Licenses");
        log.info("./Licenses present      : {}", localLicenses.isDirectory());
        log.info("Working directory       : {}", System.getProperty("user.dir"));
    }

    /**
     * Asks the SDK <em>why</em> each component failed. SERVER_OFFLINE means the licensing
     * service or Neurotechnology's server could not be reached - a connectivity or trial
     * activation problem. LICENSE_NOT_OBTAINED means it was reached and had nothing to
     * give - no entitlement for that component.
     */
    private void reportObtainedStatus(SentiveillanceProperties.Sdk sdk) {
        String port = String.valueOf(sdk.getLicensePort());
        for (String component : COMPONENTS) {
            try {
                NLicenseObtainedStatus status =
                        NLicense.getObtainedStatus(sdk.getLicenseServer(), port, component);
                log.error("Status for {}: {}", component, status);
            } catch (Exception e) {
                log.error("Status for {} could not be read: {}", component, e.toString());
            }
        }
    }

    private boolean isActivated(String component) {
        try {
            return NLicense.isComponentActivated(component);
        } catch (IOException e) {
            log.warn("Could not check activation of {}: {}", component, e.getMessage());
            return false;
        }
    }
}
