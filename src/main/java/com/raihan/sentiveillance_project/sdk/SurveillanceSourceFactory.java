package com.raihan.sentiveillance_project.sdk;

import com.neurotec.beans.NParameterBag;
import com.neurotec.beans.NParameterDescriptor;
import com.neurotec.devices.NCamera;
import com.neurotec.devices.NDevice;
import com.neurotec.devices.NDeviceManager;
import com.neurotec.media.NMediaFormat;
import com.neurotec.media.NVideoFormat;
import com.neurotec.plugins.NPlugin;
import com.neurotec.plugins.NPluginState;
import com.neurotec.surveillance.NSurveillance;
import com.neurotec.surveillance.NSurveillanceModalityType;
import com.neurotec.surveillance.NSurveillanceSource;
import com.raihan.sentiveillance_project.config.SentiveillanceProperties;
import com.raihan.sentiveillance_project.model.Source;
import com.raihan.sentiveillance_project.records.CameraDetails;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

/**
 * Turns a configured video file or RTSP camera into an {@link NSurveillanceSource}.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 8 November, 2026
 */
@Slf4j
@Component
public class SurveillanceSourceFactory {

    private static final String MEDIA_PLUGIN = "Media";

    private final SentiveillanceProperties properties;
    private final LicenseService licenseService;

    public SurveillanceSourceFactory(SentiveillanceProperties properties, LicenseService licenseService) {
        this.properties = properties;
        this.licenseService = licenseService;
    }

    public NSurveillanceSource create(NSurveillance surveillance) {
        return create(surveillance, properties.getSource());
    }

    NSurveillanceSource create(NSurveillance surveillance, Source cfg) {
        EnumSet<NSurveillanceModalityType> modalities = licenseService.resolveModalities(cfg.isDetectPeople());

        NCamera camera = connect(surveillance.getDeviceManager(), cfg);

        // The single-argument constructor plus setModalityType(EnumSet) is what the Java
        // samples use; the (type, camera) constructor only accepts one modality.
        NSurveillanceSource source = new NSurveillanceSource();
        source.setCamera(camera);
        source.setModalityType(modalities);

        log.info("Source '{}' ready: {} | modalities={}", cfg.getName(), camera, modalities);
        return source;
    }

    private NCamera connect(NDeviceManager deviceManager, Source cfg) {
        NPlugin plugin = NDeviceManager.getPluginManager().getPlugins().get(MEDIA_PLUGIN);

        if (plugin == null || plugin.getState() != NPluginState.PLUGGED) {
            throw new IllegalStateException("The SDK's 'Media' device plugin is not available");
        }

        if (!NDeviceManager.isConnectToDeviceSupported(plugin)) {
            throw new IllegalStateException("The 'Media' plugin does not support connecting to a device");
        }

        NParameterDescriptor[] parameters = NDeviceManager.getConnectToDeviceParameters(plugin);
        NParameterBag bag = new NParameterBag(parameters);
        bag.setProperty("DisplayName", cfg.getName());

        switch (cfg.getType()) {
            case VIDEO_FILE -> {
                Path file = requireVideoFile(cfg);
                bag.setProperty("FileName", file.toString());
                log.info("Connecting video file source: {}", file);
            }
            case IP_CAMERA -> {
                if (cfg.getUrl() == null || cfg.getUrl().isBlank()) {
                    throw new IllegalStateException("sentiveillance.source.url must be set for type=IP_CAMERA");
                }
                // Checked here rather than at the plugin, which reports a bad URL as an
                // opaque native error from inside ffmpeg.
                bag.setProperty("Url", CameraUrls.parse(cfg.getUrl()).url());
                log.info("Connecting IP camera source: {}", CameraUrls.mask(cfg.getUrl()));
            }
        }

        if (cfg.getFlags() != null && !cfg.getFlags().isBlank()) {
            bag.setProperty("Flags", cfg.getFlags());
        }

        NDevice device = deviceManager.connectToDevice(plugin, bag.toPropertyBag());
        if (!(device instanceof NCamera cameraDevice)) {
            throw new IllegalStateException("Media plugin returned a non-camera device: " + device);
        }

        return cameraDevice;
    }

    private Path requireVideoFile(Source cfg) {
        if (cfg.getFile() == null || cfg.getFile().isBlank()) {
            throw new IllegalStateException("sentiveillance.source.file must be set for type=VIDEO_FILE");
        }

        Path file = Path.of(cfg.getFile()).toAbsolutePath().normalize();
        if (!Files.isReadable(file)) {
            throw new IllegalStateException("Video file not readable: " + file);
        }

        return file;
    }

    /** Connects to a camera, reads what the SDK knows about it, and disconnects again. */
    CameraDetails probe(NDeviceManager deviceManager, Source cfg) {
        NCamera camera = connect(deviceManager, cfg);
        boolean started = false;
        try {
            // A network stream's format is only known once the plugin has actually read from
            // it, so pull frames briefly. Without this almost every test would answer
            // "connected, stream not negotiated yet", which says nothing about whether the
            // camera is usable. Pattern from the SDK's own devices sample, which starts
            // capture on a connected device in order to preview it.
            if (!negotiated(camera)) {
                try {
                    camera.startCapturing();
                    started = true;
                    awaitFormat(camera);
                } catch (Throwable t) {
                    log.warn("Camera connected but would not start capturing: {}", t.toString());
                }
            }
            return describe(camera, true);
        } finally {
            try {
                if (started) {
                    camera.stopCapturing();
                }
            } catch (Throwable t) {
                log.warn("Could not stop the probed camera: {}", t.toString());
            }

            try {
                if (camera.isDisconnectable()) {
                    deviceManager.disconnectFromDevice(camera);
                }
            } catch (Throwable t) {
                log.warn("Could not disconnect the probed camera: {}", t.toString());
            }
        }
    }

    /** Up to three seconds for the first frame to tell us the stream's shape. */
    private static void awaitFormat(NCamera camera) {
        for (int attempt = 0; attempt < 30 && !negotiated(camera); attempt++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static boolean negotiated(NCamera camera) {
        NMediaFormat format = quietly(camera::getCurrentFormat);
        return format instanceof NVideoFormat video
                && Boolean.TRUE.equals(quietly(() -> video.getWidth() > 0));
    }

    /** Reads identity and picture shape off a connected camera. */
    static CameraDetails describe(NCamera camera, boolean withFormats) {
        String deviceId = quietly(camera::getId);
        String displayName = quietly(camera::getDisplayName);
        String make = quietly(camera::getMake);
        String model = quietly(camera::getModel);
        String serial = quietly(camera::getSerialNumber);
        boolean available = Boolean.TRUE.equals(quietly(camera::isAvailable));
        boolean capturing = Boolean.TRUE.equals(quietly(camera::isCapturing));

        int width = 0, height = 0, formatCount = 0;
        Double frameRate = null;
        String codec = null;
        NMediaFormat current = quietly(camera::getCurrentFormat);

        if (withFormats) {
            NMediaFormat[] formats = quietly(camera::getFormats);
            if (formats != null) {
                formatCount = formats.length;
                if (current == null && formats.length > 0) {
                    current = formats[0];
                }
            }
        }

        if (current instanceof NVideoFormat video) {
            Integer w = quietly(video::getWidth);
            Integer h = quietly(video::getHeight);
            width = w == null ? 0 : w;
            height = h == null ? 0 : h;
            var rate = quietly(video::getFrameRate);

            if (rate != null && rate.denominator != 0) {
                frameRate = Math.round(rate.numerator * 100.0 / rate.denominator) / 100.0;
            }

            Integer subtype = quietly(video::getMediaSubtype);
            if (subtype != null) {
                codec = quietly(() -> video.mediaSubtypeToString(subtype));
            }
        }
        return new CameraDetails(deviceId, displayName, make, model, serial, available, capturing,
                width, height, frameRate, codec, formatCount);
    }

    /** A detail nobody should lose a dashboard request over. */
    private static <T> T quietly(java.util.function.Supplier<T> read) {
        try {
            return read.get();
        } catch (Throwable t) {
            return null;
        }
    }
}
