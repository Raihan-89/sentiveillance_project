package com.raihan.sentiveillance_project.live;

import com.neurotec.images.NImage;
import com.neurotec.images.NImageFormat;
import com.neurotec.io.NBuffer;
import com.neurotec.media.NVideoSample;
import com.neurotec.surveillance.NSurveillanceEvent;
import com.neurotec.surveillance.NSurveillanceEventDetails;
import com.neurotec.surveillance.NSurveillanceListener;
import com.raihan.sentiveillance_project.config.SentiveillanceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps the most recent video frame available as JPEG bytes so a browser can watch the feed.
 *
 * <h2>Where the frames come from</h2>
 * {@code NSurveillance.addImageCapturedListener} fires for every captured frame, and the
 * details object carries either an {@code NVideoSample} (hardware/decoder path) or a plain
 * {@code NImage}. Both are native handles valid only inside the callback, so the frame is
 * encoded to JPEG immediately and only the {@code byte[]} is kept. This mirrors the SDK's own
 * {@code FrameRender} in the Java web sample, except the JPEG is produced by the SDK's native
 * encoder ({@code NImage.save(NImageFormat.getJPEG())}) instead of going through AWT.
 *
 * <h2>Why it costs nothing when nobody is watching</h2>
 * Encoding preview frames competes with detection for CPU, and measurably so: with the
 * detector at {@code detectorScaling=ONE} on a 2160x3840 source, detection lag peaked at
 * 3.5s with nobody watching and 4.9s with a browser pulling a frame every 80ms. Frames are
 * therefore only encoded while a browser asked for one recently
 * ({@code sentiveillance.live.demand-timeout-millis}) and no more often than
 * {@code sentiveillance.live.preview-interval-millis}. With no UI open the listener does
 * nothing but dispose the handles it was given - the detection pipeline behaves exactly as
 * it did before this class existed.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 9 November, 2026
 */
@Slf4j
@Service
public class LiveFrameService {

    private final AtomicReference<byte[]> latestJpeg = new AtomicReference<>();
    private final AtomicLong framesEncoded = new AtomicLong();
    private final AtomicLong framesSkipped = new AtomicLong();

    private volatile int frameWidth;
    private volatile int frameHeight;
    private volatile long lastDemandAt;
    private volatile long lastEncodeAt;

    /** The listener instance to hand to {@code NSurveillance.addImageCapturedListener}. */
    private final NSurveillanceListener listener = this::onImageCaptured;

    private final SentiveillanceProperties properties;

    public LiveFrameService(SentiveillanceProperties properties) {
        this.properties = properties;
    }

    public NSurveillanceListener listener() {
        return listener;
    }

    /**
     * The newest encoded frame, or {@code null} if none has been produced yet. Calling this
     * also registers browser demand, which is what switches encoding on.
     */
    public byte[] latestFrame() {
        lastDemandAt = System.currentTimeMillis();
        return latestJpeg.get();
    }

    /** Source frame dimensions, needed by the browser to scale detection boxes. */
    public int frameWidth() {
        return frameWidth;
    }

    public int frameHeight() {
        return frameHeight;
    }

    public long framesEncoded() {
        return framesEncoded.get();
    }

    public long framesSkipped() {
        return framesSkipped.get();
    }

    public boolean hasFrame() {
        return latestJpeg.get() != null;
    }

    /** Dropped when the engine is torn down, so a stale frame is not served afterwards. */
    public void clear() {
        latestJpeg.set(null);
        frameWidth = 0;
        frameHeight = 0;
    }

    // ------------------------------------------------------------------
    // SDK callback - runs on a capture thread, must stay short
    // ------------------------------------------------------------------

    private void onImageCaptured(NSurveillanceEvent event) {
        NSurveillanceEventDetails[] detailsArray = event.getEventDetailsArray();
        for (NSurveillanceEventDetails details : detailsArray) {
            try {
                encodeIfWanted(details);
            } catch (Throwable t) {
                // Never let a UI problem propagate into the capture thread.
                log.debug("Could not encode a live frame: {}", t.toString());
            } finally {
                // Mandatory even on the skip path: these are native handles.
                details.dispose();
            }
        }
    }

    private void encodeIfWanted(NSurveillanceEventDetails details) {
        SentiveillanceProperties.Live cfg = properties.getLive();
        long now = System.currentTimeMillis();
        if (now - lastDemandAt > cfg.getDemandTimeoutMillis()
                || now - lastEncodeAt < cfg.getPreviewIntervalMillis()) {
            framesSkipped.incrementAndGet();
            return;
        }
        if (details.getError() != null) {
            return;
        }

        NVideoSample sample = details.getVideoSample(false);
        NImage image = null;
        try {
            image = sample != null ? sample.toImage() : details.getOriginalImage(false);
            if (image == null) {
                return;
            }
            frameWidth = image.getWidth();
            frameHeight = image.getHeight();

            NBuffer buffer = image.save(NImageFormat.getJPEG());
            try {
                latestJpeg.set(buffer.toByteArray());
            } finally {
                buffer.dispose();
            }
            lastEncodeAt = now;
            framesEncoded.incrementAndGet();
        } finally {
            if (image != null) {
                image.dispose();
            }
            if (sample != null) {
                sample.dispose();
            }
        }
    }
}
