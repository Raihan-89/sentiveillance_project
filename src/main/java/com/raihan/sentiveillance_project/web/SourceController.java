package com.raihan.sentiveillance_project.web;

import com.raihan.sentiveillance_project.config.SentiveillanceProperties;
import com.raihan.sentiveillance_project.sdk.SurveillanceEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.InvalidPathException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Choosing which video the engine runs on, from the dashboard rather than the config file.
 *
 * <p>Uploaded files are kept in {@code source.uploads-directory} instead of a temp file: the
 * SDK is handed a path and reads from it for as long as the source is attached, well after the
 * upload request has returned. Keeping them also means a video can be replayed later without
 * uploading it again, which is what {@code GET /api/source/videos} lists.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 9 November, 2026
 */
@Slf4j
@RestController
@RequestMapping("/api/source")
public class SourceController {

    /** Containers the SDK's Media plugin handles through FFmpeg or VLC. */
    private static final Set<String> VIDEO_EXTENSIONS =
            Set.of("mp4", "avi", "mkv", "mov", "m4v", "mpg", "mpeg", "wmv", "flv", "webm", "ts");

    private final SentiveillanceProperties properties;
    private final SurveillanceEngine engine;

    public SourceController(SentiveillanceProperties properties, SurveillanceEngine engine) {
        this.properties = properties;
        this.engine = engine;
    }

    /** The video playing now, plus everything available to switch to. */
    @GetMapping("/videos")
    public Map<String, Object> videos() throws IOException {
        Path dir = uploadsDirectory();
        List<Map<String, Object>> files = List.of();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> entries = Files.list(dir)) {
                files = entries
                        .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .filter(p -> isVideo(p.getFileName().toString()))
                        .sorted(Comparator.comparing((Path p) -> lastModified(p)).reversed())
                        .map(SourceController::describe)
                        .toList();
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("current", properties.getSource().getName());
        body.put("currentFile", properties.getSource().getFile());
        body.put("videos", files);
        return body;
    }

    /**
     * Accepts a video from the browser and switches to it.
     *
     * <p>Only the base name of the upload is used, and the result is checked to still sit
     * inside the uploads directory, so a crafted name cannot write elsewhere.
     */
    @PostMapping("/video")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file was uploaded"));
        }
        String original = file.getOriginalFilename();
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.isBlank() || name.indexOf('\0') >= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid file name"));
        }
        if (!isVideo(name)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Not a video this SDK reads. Supported: " + VIDEO_EXTENSIONS));
        }
        try {
            Path dir = uploadsDirectory();
            Files.createDirectories(dir);
            // A unique path also protects an active source from same-name uploads.
            int dot = name.lastIndexOf('.');
            String stem = name.substring(0, dot).replaceAll("[^\\p{L}\\p{N}._-]", "_");
            if (stem.length() > 80) stem = stem.substring(0, 80);
            Path target = Files.createTempFile(dir, stem + "-", name.substring(dot).toLowerCase(Locale.ROOT));
            try (var in = file.getInputStream()) {
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(target);
                throw e;
            }
            log.info("Received video {} ({} bytes)", target.getFileName(), Files.size(target));
            return switchTo(target);
        } catch (IOException e) {
            log.error("Could not store the uploaded video", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Could not store the uploaded video"));
        }
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> uploadTooLarge() {
        return ResponseEntity.status(413).body(Map.of("error", "Video exceeds the configured upload size limit"));
    }

    /** Switches to a video already in the uploads directory. */
    @PostMapping("/video/{name}")
    public ResponseEntity<?> select(@PathVariable String name) throws IOException {
        Path dir = uploadsDirectory();
        Path target;
        try {
            target = dir.resolve(name).normalize();
        } catch (InvalidPathException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid file name"));
        }
        if (!dir.equals(target.getParent()) || !isVideo(name)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return ResponseEntity.status(404).body(Map.of("error", "Uploaded video not found"));
        }
        return switchTo(target);
    }

    private ResponseEntity<?> switchTo(Path video) {
        try {
            String name = engine.useVideoFile(video);
            return ResponseEntity.ok(Map.of("current", name, "running", engine.isRunning()));
        } catch (IllegalStateException e) {
            // The engine builds its source at startup; without it there is nothing to swap.
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Could not switch to {}", video, e);
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private Path uploadsDirectory() {
        return Path.of(properties.getSource().getUploadsDirectory()).toAbsolutePath().normalize();
    }

    private static boolean isVideo(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && VIDEO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static Map<String, Object> describe(Path p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getFileName().toString());
        try {
            m.put("bytes", Files.size(p));
        } catch (IOException e) {
            m.put("bytes", 0L);
        }
        return m;
    }
}
