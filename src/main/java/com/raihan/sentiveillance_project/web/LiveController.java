package com.raihan.sentiveillance_project.web;

import com.raihan.sentiveillance_project.config.SentiveillanceProperties;
import com.raihan.sentiveillance_project.live.LiveEventStream;
import com.raihan.sentiveillance_project.live.LiveFrameService;
import com.raihan.sentiveillance_project.live.RecognizedPeopleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything the dashboard needs that the JSON API did not already expose: the live video
 * feed, the event push channel, and the snapshot files.
 *
 * <p>The video feed is served as {@code multipart/x-mixed-replace} (MJPEG), which an
 * {@code <img>} tag renders natively - no player, no JavaScript decoding, and it works while
 * the SDK keeps ownership of the actual decoding.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 9 November, 2026
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class LiveController {

    private static final String BOUNDARY = "sentiveillanceframe";

    private final LiveFrameService frames;
    private final LiveEventStream events;
    private final RecognizedPeopleService recognizedPeople;
    private final SentiveillanceProperties properties;

    public LiveController(LiveFrameService frames,
                          LiveEventStream events,
                          SentiveillanceProperties properties,
                          RecognizedPeopleService recognizedPeople) {
        this.frames = frames;
        this.events = events;
        this.properties = properties;
        this.recognizedPeople = recognizedPeople;
    }

    /** Dimensions and counters, so the page can scale detection boxes onto the video. */
    @GetMapping("/live/meta")
    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hasFrame", frames.hasFrame());
        m.put("frameWidth", frames.frameWidth());
        m.put("frameHeight", frames.frameHeight());
        m.put("framesEncoded", frames.framesEncoded());
        m.put("framesSkipped", frames.framesSkipped());
        m.put("viewers", events.viewers());
        m.put("sourceName", properties.getSource().getName());
        m.put("sourceType", String.valueOf(properties.getSource().getType()));
        return m;
    }

    /** A single still, for the poster image and as a fallback if MJPEG is blocked. */
    @GetMapping(value = "/live/frame.jpg", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> frame() {
        byte[] jpeg = frames.latestFrame();
        if (jpeg == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_JPEG)
                .body(jpeg);
    }

    @GetMapping("/live/recognized")
    public ResponseEntity<java.util.List<RecognizedPeopleService.Person>> recognized() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(recognizedPeople.list());
    }

    @GetMapping(value = "/live/recognized/{id}/face.jpg", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> recognizedFace(@PathVariable String id) {
        byte[] face = recognizedPeople.face(id);
        if (face == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_JPEG).body(face);
    }

    /** The live feed. Ends by itself when the browser closes the connection. */
    @GetMapping("/live/stream.mjpg")
    public ResponseEntity<StreamingResponseBody> stream() {
        StreamingResponseBody body = out -> {
            byte[] previous = null;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    byte[] jpeg = frames.latestFrame();
                    // Identity check: only push when the encoder produced something new.
                    if (jpeg != null && jpeg != previous) {
                        writePart(out, jpeg);
                        previous = jpeg;
                    }
                    // Poll a little faster than the encoder produces, so a new frame is
                    // forwarded promptly without busy-waiting.
                    Thread.sleep(Math.max(20L, properties.getLive().getPreviewIntervalMillis() / 2));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // Normal: the tab was closed or navigated away.
                log.debug("MJPEG client disconnected: {}", e.toString());
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "multipart/x-mixed-replace; boundary=" + BOUNDARY)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private static void writePart(OutputStream out, byte[] jpeg) throws IOException {
        String header = "--" + BOUNDARY + "\r\n"
                + "Content-Type: image/jpeg\r\n"
                + "Content-Length: " + jpeg.length + "\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(jpeg);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Detection events as they happen. */
    @GetMapping(value = "/live/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter liveEvents() {
        return events.register();
    }

    /**
     * Serves a snapshot JPEG by file name.
     *
     * <p>Only the file name is accepted and the resolved path is checked to still be inside
     * the configured directory, so a crafted name cannot read arbitrary files.
     */
    @GetMapping(value = "/snapshots/{name}", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> snapshot(@PathVariable String name) throws IOException {
        Path dir = Path.of(properties.getSnapshots().getDirectory()).toAbsolutePath().normalize();
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)))
                .contentType(MediaType.IMAGE_JPEG)
                .body(Files.readAllBytes(file));
    }
}
