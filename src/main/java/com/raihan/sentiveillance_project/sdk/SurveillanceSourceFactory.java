package com.raihan.sentiveillance_project.sdk;

import com.neurotec.beans.NParameterBag;
import com.neurotec.beans.NParameterDescriptor;
import com.neurotec.devices.NCamera;
import com.neurotec.devices.NDevice;
import com.neurotec.devices.NDeviceManager;
import com.neurotec.plugins.NPlugin;
import com.neurotec.plugins.NPluginState;
import com.neurotec.surveillance.NSurveillance;
import com.neurotec.surveillance.NSurveillanceModalityType;
import com.neurotec.surveillance.NSurveillanceSource;
import com.raihan.sentiveillance_project.config.SentiveillanceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

/**
 * Turns a configured video file or RTSP camera into an {@link NSurveillanceSource}.
 *
 * <p>Both cases go through the same door: the SDK's {@code "Media"} device plugin. You ask
 * the plugin which parameters it accepts, fill a {@link NParameterBag}, and
 * {@code connectToDevice} hands back an {@link NCamera}. A file uses the {@code FileName}
 * parameter, a network camera uses {@code Url} - that is the only difference, which is
 * exactly why one camera today extends to many cameras later without touching this class.
 *
 * <p>Pattern copied from {@code Tutorials/Surveillance/CS/CountSubjectsCS/Program.cs}
 * ({@code ConnectDevice}) and the Java web sample's {@code addSource}.
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
        SentiveillanceProperties.Source cfg = properties.getSource();
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

    private NCamera connect(NDeviceManager deviceManager, SentiveillanceProperties.Source cfg) {
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
                bag.setProperty("Url", cfg.getUrl());
                log.info("Connecting IP camera source: {}", maskCredentials(cfg.getUrl()));
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

    private Path requireVideoFile(SentiveillanceProperties.Source cfg) {
        if (cfg.getFile() == null || cfg.getFile().isBlank()) {
            throw new IllegalStateException("sentiveillance.source.file must be set for type=VIDEO_FILE");
        }
        Path file = Path.of(cfg.getFile()).toAbsolutePath().normalize();
        if (!Files.isReadable(file)) {
            throw new IllegalStateException("Video file not readable: " + file);
        }
        return file;
    }

    /** Keeps the rtsp password out of the log. */
    static String maskCredentials(String url) {
        return url.replaceAll("://[^/@]*@", "://***:***@");
    }
}
