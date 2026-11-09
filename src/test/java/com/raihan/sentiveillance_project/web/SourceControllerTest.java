package com.raihan.sentiveillance_project.web;

import com.raihan.sentiveillance_project.config.SentiveillanceProperties;
import com.raihan.sentiveillance_project.sdk.SurveillanceEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SourceControllerTest {
    @TempDir Path uploads;
    SurveillanceEngine engine;
    SourceController controller;

    @BeforeEach
    void setUp() {
        var properties = new SentiveillanceProperties();
        properties.getSource().setUploadsDirectory(uploads.toString());
        engine = mock(SurveillanceEngine.class);
        controller = new SourceController(properties, engine);
        when(engine.useVideoFile(any())).thenAnswer(call ->
                ((Path) call.getArgument(0)).getFileName().toString());
    }

    @Test
    void uploadStoresBytesAndStartsSourceWithoutOverwritingSameName() throws Exception {
        Path existing = Files.writeString(uploads.resolve("clip.mp4"), "original");
        var file = new MockMultipartFile("file", "clip.mp4", "video/mp4", "new video".getBytes());
        assertEquals(200, controller.upload(file).getStatusCode().value());
        assertEquals(200, controller.upload(file).getStatusCode().value());
        assertEquals("original", Files.readString(existing));
        var paths = org.mockito.ArgumentCaptor.forClass(Path.class);
        verify(engine, times(2)).useVideoFile(paths.capture());
        assertNotEquals(paths.getAllValues().get(0), paths.getAllValues().get(1));
        for (Path path : paths.getAllValues()) {
            assertEquals(uploads, path.getParent());
            assertEquals("new video", Files.readString(path));
        }
    }

    @Test
    void rejectsEmptyAndUnsupportedUploads() {
        assertEquals(400, controller.upload(new MockMultipartFile("file", new byte[0])).getStatusCode().value());
        assertEquals(400, controller.upload(new MockMultipartFile("file", "clip.txt", "text/plain", new byte[]{1})).getStatusCode().value());
        verifyNoInteractions(engine);
    }

    @Test
    void handlesWindowsPathsAndUppercaseExtension() {
        var file = new MockMultipartFile("file", "C:\\Videos\\clip.MP4", "video/mp4", new byte[]{1});
        assertEquals(200, controller.upload(file).getStatusCode().value());
        verify(engine).useVideoFile(argThat(path -> path.getParent().equals(uploads)
                && path.getFileName().toString().startsWith("clip-")
                && path.toString().endsWith(".mp4")));
    }

    @Test
    void selectsOnlyExistingVideosInsideUploads() throws Exception {
        Files.writeString(uploads.resolve("clip.mp4"), "video");
        Files.writeString(uploads.resolve("notes.txt"), "notes");
        assertEquals(404, controller.select("../outside.mp4").getStatusCode().value());
        assertEquals(404, controller.select("missing.mp4").getStatusCode().value());
        assertEquals(404, controller.select("notes.txt").getStatusCode().value());
        assertEquals(200, controller.select("clip.mp4").getStatusCode().value());
        verify(engine).useVideoFile(uploads.resolve("clip.mp4"));
    }

    @Test
    void excludesSymlinksFromListAndSelection() throws Exception {
        Path real = Files.writeString(uploads.resolve("real.mp4"), "video");
        Files.createSymbolicLink(uploads.resolve("linked.mp4"), real);
        assertEquals(404, controller.select("linked.mp4").getStatusCode().value());
        var videos = (List<?>) controller.videos().get("videos");
        assertEquals(1, videos.size());
        assertEquals("real.mp4", ((Map<?, ?>) videos.getFirst()).get("name"));
    }

    @Test
    void reportsEngineUnavailableAndOversizeUploads() {
        doThrow(new IllegalStateException("Engine is not started")).when(engine).useVideoFile(any());
        assertEquals(409, controller.upload(new MockMultipartFile("file", "clip.mp4", "video/mp4", new byte[]{1})).getStatusCode().value());
        assertEquals(413, controller.uploadTooLarge().getStatusCode().value());
    }
}
