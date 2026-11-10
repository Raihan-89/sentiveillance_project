package com.raihan.sentiveillance_project.web;

import com.raihan.sentiveillance_project.camera.CameraForm;
import com.raihan.sentiveillance_project.camera.CameraService;
import com.raihan.sentiveillance_project.domain.Camera;
import com.raihan.sentiveillance_project.sdk.CameraDetails;
import com.raihan.sentiveillance_project.sdk.CameraUrls;
import com.raihan.sentiveillance_project.sdk.SurveillanceEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Managing IP cameras from the dashboard: list, add, test, use, remove.
 *
 * <p>Deliberately separate from {@link SourceController}, which does the video-file half of the
 * same job. They share the {@code /api/source} prefix because to the dashboard it is one
 * question - what is the engine looking at - but the mechanics have nothing in common: a file
 * is uploaded and lives on disk, a camera is a saved address that may or may not answer.
 *
 * <p>Errors are mapped rather than thrown as 500s, because almost every failure here is
 * something the operator can fix: a malformed URL (400), a camera that is unreachable or an
 * engine that is not running (409).
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 10 November, 2026
 */
@Slf4j
@RestController
@RequestMapping("/api/source/cameras")
public class CameraController {

    private final CameraService cameras;
    private final SurveillanceEngine engine;

    public CameraController(CameraService cameras, SurveillanceEngine engine) {
        this.cameras = cameras;
        this.engine = engine;
    }

    @GetMapping
    public Map<String, Object> list() {
        var all = cameras.list();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cameras", all);
        body.put("activeId", all.stream().filter(CameraService.CameraView::active)
                .map(CameraService.CameraView::id).findFirst().orElse(null));
        return body;
    }

    /** Saves a camera. {@code ?activate=true} also makes it the live source. */
    @PostMapping
    public Map<String, Object> add(@RequestBody CameraForm form,
                                   @RequestParam(defaultValue = "false") boolean activate) {
        Camera saved = cameras.save(form);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", saved.getId());
        body.put("name", saved.getName());
        body.put("url", CameraUrls.mask(saved.getUrl()));
        if (activate) {
            body.put("current", cameras.activate(saved.getId()));
            body.put("running", engine.isRunning());
        }
        return body;
    }

    /** Tests a camera that is not saved yet - the "Test" button on the add form. */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody CameraForm form) {
        return describe(cameras.test(form), form.resolveUrl());
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable long id) {
        Camera camera = cameras.require(id);
        return describe(cameras.test(id), camera.getUrl());
    }

    @PostMapping("/{id}/activate")
    public Map<String, Object> activate(@PathVariable long id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("current", cameras.activate(id));
        body.put("running", engine.isRunning());
        return body;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        cameras.delete(id);
        return Map.of("deleted", id);
    }

    /** Flattens what the SDK said, plus the URL it was reached at, with the password hidden. */
    private static Map<String, Object> describe(CameraDetails details, String url) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", CameraUrls.mask(url));
        body.put("reachable", true);
        body.put("camera", details);
        return body;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> conflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    /**
     * Anything else is the SDK refusing the stream - wrong credentials, an unsupported
     * container, RTSP disabled - reported as a native error whose message is still the most
     * useful thing we can show.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> sdkFailure(RuntimeException e) {
        log.warn("Camera request failed: {}", e.toString());
        return ResponseEntity.status(502).body(Map.of("error",
                e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage()));
    }
}
