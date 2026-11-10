package com.raihan.sentiveillance_project.camera;

import com.raihan.sentiveillance_project.config.SentiveillanceProperties;
import com.raihan.sentiveillance_project.domain.Camera;
import com.raihan.sentiveillance_project.domain.CameraRepository;
import com.raihan.sentiveillance_project.sdk.CameraDetails;
import com.raihan.sentiveillance_project.sdk.CameraUrls;
import com.raihan.sentiveillance_project.sdk.SurveillanceEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The IP-camera half of source selection: a saved list of cameras, a test that reaches one
 * without disturbing the live pipeline, and making one the live source.
 *
 * <h2>Why a test step exists</h2>
 * A video file either opens or it does not. A camera has three separate ways to fail, and the
 * SDK reports all of them the same way - as a native error from inside the Media plugin - so
 * they are separated here before the SDK is ever involved:
 * <ol>
 *   <li>the URL is malformed ({@link CameraUrls#parse}),</li>
 *   <li>the host or RTSP port is unreachable (a plain TCP connect, below),</li>
 *   <li>the stream itself is refused - wrong credentials, wrong path, RTSP disabled - which
 *       only the plugin can tell us, via {@link SurveillanceEngine#probeCamera}.</li>
 * </ol>
 * The payoff is that the dashboard can say which of the three happened.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 10 November, 2026
 */
@Slf4j
@Service
public class CameraService {

    /** A camera on a LAN answers a TCP connect in milliseconds; this is only a sanity bound. */
    private static final int REACH_TIMEOUT_MILLIS = 3_000;
    /**
     * How long to wait for the plugin to negotiate the stream. ffmpeg's own RTSP timeouts are
     * in this range, so a shorter wait here would report "no answer" for cameras that were
     * about to connect.
     */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);

    private final CameraRepository cameras;
    private final SurveillanceEngine engine;
    private final SentiveillanceProperties properties;

    public CameraService(CameraRepository cameras, SurveillanceEngine engine,
                         SentiveillanceProperties properties) {
        this.cameras = cameras;
        this.engine = engine;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ listing

    @Transactional(readOnly = true)
    public List<CameraView> list() {
        String activeUrl = activeCameraUrl();
        return cameras.findAllByOrderByNameAsc().stream()
                .map(camera -> CameraView.of(camera, camera.getUrl().equals(activeUrl)))
                .toList();
    }

    /** The URL of the camera currently attached to the engine, or null if a file is playing. */
    private String activeCameraUrl() {
        SentiveillanceProperties.Source cfg = properties.getSource();
        return cfg.getType() == SentiveillanceProperties.Source.Type.IP_CAMERA ? cfg.getUrl() : null;
    }

    // ------------------------------------------------------------------ editing

    /**
     * Saves a camera, or returns the existing row if that URL is already known - re-adding the
     * same camera should not create a duplicate that both claim to be the live source.
     */
    @Transactional
    public Camera save(CameraForm form) {
        String url = form.resolveUrl();
        Camera camera = cameras.findByUrl(url).orElseGet(Camera::new);
        camera.setUrl(url);
        camera.setFlags(form.trimmedFlags());
        String name = form.trimmedName();
        if (name == null) {
            name = camera.getName() != null ? camera.getName() : CameraUrls.parse(url).host();
        }
        camera.setName(uniqueName(name, camera.getId()));
        return cameras.save(camera);
    }

    /** Names are what detection rows are stamped with, so they have to stay distinguishable. */
    private String uniqueName(String wanted, Long selfId) {
        String candidate = wanted.length() > 200 ? wanted.substring(0, 200) : wanted;
        for (int suffix = 2; ; suffix++) {
            var clash = cameras.findByNameIgnoreCase(candidate);
            if (clash.isEmpty() || java.util.Objects.equals(clash.get().getId(), selfId)) {
                return candidate;
            }
            candidate = wanted + "-" + suffix;
        }
    }

    @Transactional
    public void delete(long id) {
        Camera camera = require(id);
        if (camera.getUrl().equals(activeCameraUrl())) {
            throw new IllegalStateException("That camera is the live source - switch away from it first");
        }
        cameras.delete(camera);
    }

    @Transactional(readOnly = true)
    public Camera require(long id) {
        return cameras.findById(id).orElseThrow(() -> new IllegalArgumentException("No camera with id " + id));
    }

    // ------------------------------------------------------------------ testing

    /**
     * Tests a saved camera and remembers what it answered.
     *
     * <p>Not {@code @Transactional}: the SDK call in the middle can take twenty seconds, and a
     * database connection should not be held open across it. The two repository calls each run
     * in their own transaction, and the entity is merged back on save.
     */
    public CameraDetails test(long id) {
        Camera camera = require(id);
        try {
            CameraDetails details = probe(camera.getName(), camera.getUrl(), camera.getFlags());
            recordSuccess(camera, details, null);
            return details;
        } catch (RuntimeException e) {
            recordFailure(camera, e);
            throw e;
        }
    }

    /** Tests a camera that has not been saved yet, so a bad URL never reaches the table. */
    public CameraDetails test(CameraForm form) {
        return probe(form.trimmedName(), form.resolveUrl(), form.trimmedFlags());
    }

    private CameraDetails probe(String name, String url, String flags) {
        CameraUrls.Parsed parsed = CameraUrls.parse(url);
        requireReachable(parsed);
        return engine.probeCamera(name, parsed.url(), flags, PROBE_TIMEOUT);
    }

    /**
     * Fails fast, and with a message that says what to check, when the camera is simply not
     * there. Without this the SDK is handed an unreachable address and the browser waits for
     * ffmpeg's timeout to decide the same thing.
     */
    private void requireReachable(CameraUrls.Parsed parsed) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(parsed.host(), parsed.port()), REACH_TIMEOUT_MILLIS);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Cannot resolve '" + parsed.host()
                    + "'. Use the camera's IP address, or check DNS.");
        } catch (IOException e) {
            throw new IllegalStateException("Nothing answered at " + parsed.host() + ":" + parsed.port()
                    + ". Check the camera is powered and on this network, and that RTSP is enabled"
                    + " (camera web UI: Setup > Network > RTSP).");
        }
    }

    // ------------------------------------------------------------- activating

    /**
     * Makes a saved camera the live source.
     *
     * <p>Reachability is checked first for the same reason as in a test: the engine's switch
     * blocks inside the plugin until the stream opens or times out, and an unplugged camera
     * should come back as a sentence rather than a hung request. Not {@code @Transactional},
     * for the same reason {@link #test(long)} is not.
     */
    public String activate(long id) {
        Camera camera = require(id);
        requireReachable(CameraUrls.parse(camera.getUrl()));
        try {
            String name = engine.useIpCamera(camera.getName(), camera.getUrl(), camera.getFlags());
            camera.setLastUsedAt(Instant.now());
            recordSuccess(camera, engine.sourceDetails(), "Live source");
            log.info("Live source is now camera '{}' ({})", name, CameraUrls.mask(camera.getUrl()));
            return name;
        } catch (RuntimeException e) {
            recordFailure(camera, e);
            throw e;
        }
    }

    // --------------------------------------------------------------- bookkeeping

    private void recordSuccess(Camera camera, CameraDetails details, String note) {
        camera.setLastSeenAt(Instant.now());
        camera.setLastOk(true);
        if (details != null) {
            if (details.width() > 0) camera.setLastWidth(details.width());
            if (details.height() > 0) camera.setLastHeight(details.height());
            if (details.frameRate() != null) camera.setLastFrameRate(details.frameRate());
            if (details.codec() != null) camera.setLastCodec(details.codec());
            if (details.make() != null) camera.setLastMake(details.make());
            if (details.model() != null) camera.setLastModel(details.model());
        }
        camera.setLastResult(note != null ? note
                : details == null ? "Connected"
                : details.width() > 0
                        ? "Connected: " + details.width() + "x" + details.height()
                                + (details.codec() == null ? "" : " " + details.codec())
                        : "Connected, stream not negotiated yet");
        cameras.save(camera);
    }

    private void recordFailure(Camera camera, RuntimeException e) {
        camera.setLastSeenAt(Instant.now());
        camera.setLastOk(false);
        camera.setLastResult(shorten(e.getMessage() == null ? e.toString() : e.getMessage()));
        cameras.save(camera);
    }

    private static String shorten(String message) {
        return message.length() <= 500 ? message : message.substring(0, 497) + "...";
    }

    // -------------------------------------------------------------- bootstrap

    /**
     * Puts the camera from {@code application.properties} into the list, so a camera configured
     * at boot is also selectable from the dashboard - and can be switched back to after a video
     * file has been played.
     *
     * <p>Runs after the application is ready, which is after {@code SurveillanceStarter}: by
     * then {@code source.url} reflects whatever actually got attached.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void importConfiguredCamera() {
        SentiveillanceProperties.Source cfg = properties.getSource();
        if (cfg.getType() != SentiveillanceProperties.Source.Type.IP_CAMERA
                || cfg.getUrl() == null || cfg.getUrl().isBlank()) {
            return;
        }
        try {
            String url = CameraUrls.parse(cfg.getUrl()).url();
            if (cameras.findByUrl(url).isPresent()) {
                return;
            }
            Camera camera = new Camera();
            camera.setUrl(url);
            camera.setFlags(cfg.getFlags() == null || cfg.getFlags().isBlank() ? null : cfg.getFlags());
            camera.setName(uniqueName(cfg.getName() == null ? CameraUrls.parse(url).host() : cfg.getName(), null));
            camera.setLastResult("Imported from application.properties");
            cameras.save(camera);
            log.info("Registered the configured camera '{}' ({}) in the camera list",
                    camera.getName(), CameraUrls.mask(url));
        } catch (RuntimeException e) {
            log.warn("Could not register the configured camera: {}", e.getMessage());
        }
    }

    /**
     * A camera as the dashboard sees it - URL masked, so the camera password never leaves the
     * server even though the row holds it.
     *
     * @author Md. Raihan Shikder (Raihan-89)
     * @date 10 November, 2026
     */
    public record CameraView(Long id, String name, String url, String flags, boolean active,
                             Instant createdAt, Instant lastSeenAt, Instant lastUsedAt,
                             String lastResult, Boolean lastOk, Integer width, Integer height,
                             Double frameRate, String codec, String make, String model) {

        static CameraView of(Camera camera, boolean active) {
            return new CameraView(camera.getId(), camera.getName(), CameraUrls.mask(camera.getUrl()),
                    camera.getFlags(), active, camera.getCreatedAt(), camera.getLastSeenAt(),
                    camera.getLastUsedAt(), camera.getLastResult(), camera.getLastOk(),
                    camera.getLastWidth(), camera.getLastHeight(), camera.getLastFrameRate(),
                    camera.getLastCodec(), camera.getLastMake(), camera.getLastModel());
        }
    }
}
