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

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    /** Amber: tracked but not matched to anyone enrolled. */
    private static final Color UNKNOWN_COLOUR = new Color(0xFF, 0xB3, 0x47);
    /** Green: matched against the watch list. */
    private static final Color KNOWN_COLOUR = new Color(0x37, 0xD6, 0x7A);
    private static final Color LABEL_TEXT = new Color(0x06, 0x10, 0x1C);
    /** A box is drawn for this long after its last update, then dropped. */
    private static final long BOX_LIFETIME_MILLIS = 1_500L;

    /** traceIndex -> the box to draw for that subject. */
    private final Map<Integer, OverlayBox> boxes = new ConcurrentHashMap<>();

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
        boxes.clear();
        frameWidth = 0;
        frameHeight = 0;
    }

    /**
     * Records where a subject currently is, so the next encoded frame carries their box.
     *
     * <p>Called from the detection path with coordinates in full-resolution frame space; the
     * scaling down to preview size happens at draw time, where the ratio is known.
     */
    public void showBox(int traceIndex, int left, int top, int width, int height,
                        String label, boolean identified) {
        boxes.put(traceIndex, new OverlayBox(left, top, width, height, label, identified,
                System.currentTimeMillis()));
    }

    /** Called when a subject leaves the frame. */
    public void hideBox(int traceIndex) {
        boxes.remove(traceIndex);
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


    /**
     * Scales the frame down for the browser and draws the detection boxes straight onto it.
     *
     * <h2>Why the boxes are burned in rather than drawn by the page</h2>
     * The browser was drawing them on a canvas over the video, which meant it had to scale
     * source-frame coordinates to the displayed image size and guess which frame a box
     * belonged to. Doing it here removes both problems: the rectangle is painted in the same
     * pixel space it was measured in, so it cannot be misplaced, and whatever the page
     * receives already has the boxes on it.
     *
     * <p>{@code writeTo} hands the pixels straight to a {@link BufferedImage}, so there is no
     * decode of an intermediate JPEG. Drawing happens at preview size, not full size, which is
     * also what makes it cheap: a 2160x3840 frame is ~8 megapixels, the preview is a fraction
     * of that, and the JPEG that goes over the wire shrinks accordingly.
     */
    private byte[] render(NImage image) throws java.io.IOException {
        int srcW = image.getWidth();
        int srcH = image.getHeight();
        int maxHeight = properties.getLive().getPreviewMaxHeight();

        // Never upscale: a source smaller than the cap is left alone.
        double scale = maxHeight > 0 && srcH > maxHeight ? (double) maxHeight / srcH : 1.0;
        int dstW = Math.max(1, (int) Math.round(srcW * scale));
        int dstH = Math.max(1, (int) Math.round(srcH * scale));

        BufferedImage full = new BufferedImage(srcW, srcH, BufferedImage.TYPE_INT_RGB);
        image.writeTo(full, false);

        BufferedImage frame;
        if (scale == 1.0) {
            frame = full;
        } else {
            frame = new BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = frame.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(full, 0, 0, dstW, dstH, null);
            g.dispose();
            full.flush();
        }

        drawBoxes(frame, scale);
        return toJpeg(frame);
    }

    private void drawBoxes(BufferedImage frame, double scale) {
        long now = System.currentTimeMillis();
        boxes.values().removeIf(b -> now - b.atMillis() > BOX_LIFETIME_MILLIS);
        if (boxes.isEmpty()) {
            return;
        }

        Graphics2D g = frame.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int fontSize = Math.max(11, frame.getHeight() / 34);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
            float stroke = Math.max(2f, frame.getHeight() / 220f);

            for (OverlayBox box : boxes.values()) {
                int x = (int) Math.round(box.left() * scale);
                int y = (int) Math.round(box.top() * scale);
                int w = (int) Math.round(box.width() * scale);
                int h = (int) Math.round(box.height() * scale);
                Color colour = box.identified() ? KNOWN_COLOUR : UNKNOWN_COLOUR;

                g.setStroke(new BasicStroke(stroke));
                g.setColor(colour);
                g.drawRect(x, y, w, h);

                String label = box.label();
                if (label == null || label.isBlank()) {
                    continue;
                }
                int textWidth = g.getFontMetrics().stringWidth(label);
                int pad = Math.max(3, fontSize / 3);
                int boxH = fontSize + pad * 2;
                // Above the rectangle, unless that would fall off the top of the frame.
                int labelY = y - boxH < 0 ? y + h : y - boxH;
                g.setColor(colour);
                g.fillRect(x, labelY, textWidth + pad * 2, boxH);
                g.setColor(LABEL_TEXT);
                g.drawString(label, x + pad, labelY + boxH - pad - 1);
            }
        } finally {
            g.dispose();
        }
    }

    private static byte[] toJpeg(BufferedImage frame) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128 * 1024);
        if (!ImageIO.write(frame, "jpg", out)) {
            throw new java.io.IOException("No JPEG writer available");
        }
        frame.flush();
        return out.toByteArray();
    }

    private void encodeIfWanted(NSurveillanceEventDetails details) throws java.io.IOException {
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

            latestJpeg.set(render(image));
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
