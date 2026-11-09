package com.raihan.sentiveillance_project.live;

import com.raihan.sentiveillance_project.detection.SubjectEventSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes detection events to every open browser over Server-Sent Events.
 *
 * <p>SSE rather than WebSockets because the traffic is one-way and a plain
 * {@code EventSource} in the page reconnects by itself. Emitters are created with no timeout
 * and removed on completion, timeout or error, so a closed tab cannot accumulate.
 *
 * <p>{@link #publish} is called from the detection writer thread and never throws: a dead
 * browser connection must not disturb recording.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 9 November, 2026
 */
@Slf4j
@Service
public class LiveEventStream {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(0L); // 0 = never time out
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        try {
            emitter.send(SseEmitter.event().name("hello").data(Map.of("viewers", emitters.size())));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    public int viewers() {
        return emitters.size();
    }

    /**
     * @param snapshot     the event as recorded
     * @param employeeName resolved watch-list name, or {@code null} when unidentified
     */
    public void publish(SubjectEventSnapshot snapshot, String employeeName) {
        if (emitters.isEmpty()) {
            return;
        }
        Map<String, Object> payload = toPayload(snapshot, employeeName);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("detection").data(payload));
            } catch (Exception e) {
                // Browser went away mid-send.
                emitters.remove(emitter);
            }
        }
    }

    private static Map<String, Object> toPayload(SubjectEventSnapshot s, String employeeName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", s.sourceName());
        m.put("eventType", s.eventType());
        m.put("traceIndex", s.traceIndex());
        m.put("eventTime", s.eventTime() == null ? null : s.eventTime().toString());
        m.put("frameIndex", s.frameIndex());
        m.put("objectType", s.objectType());
        m.put("hasFaceDetails", s.hasFaceDetails());
        m.put("matchedTemplateId", s.matchedTemplateId());
        m.put("matchScore", s.matchScore());
        m.put("employee", employeeName);
        m.put("rect", s.rectLeft() == null ? null : Map.of(
                "left", s.rectLeft(),
                "top", s.rectTop(),
                "width", s.rectWidth(),
                "height", s.rectHeight()));
        // Only the file name travels - the browser fetches it from /api/snapshots/{name}.
        m.put("snapshot", s.snapshotPath() == null
                ? null
                : Path.of(s.snapshotPath()).getFileName().toString());
        return m;
    }
}
