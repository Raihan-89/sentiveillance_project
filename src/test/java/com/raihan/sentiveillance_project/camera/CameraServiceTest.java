package com.raihan.sentiveillance_project.camera;

import com.raihan.sentiveillance_project.config.SentiveillanceProperties;
import com.raihan.sentiveillance_project.domain.Camera;
import com.raihan.sentiveillance_project.enums.SourceType;
import com.raihan.sentiveillance_project.records.CameraForm;
import com.raihan.sentiveillance_project.repository.CameraRepository;
import com.raihan.sentiveillance_project.records.CameraView;
import com.raihan.sentiveillance_project.sdk.SurveillanceEngine;
import com.raihan.sentiveillance_project.service.CameraService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The bookkeeping half of camera handling - everything that does not need a camera on the
 * network. Reaching a camera is covered by {@code scripts/check-camera.sh} and the dashboard's
 * own Test button, which both need real hardware.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 10 November, 2026
 */
class CameraServiceTest {

    static final String URL = "rtsp://svc:secret@192.168.1.108:554/cam/realmonitor?channel=1&subtype=0";

    CameraRepository repository;
    SurveillanceEngine engine;
    SentiveillanceProperties properties;
    CameraService service;
    List<Camera> stored;

    @BeforeEach
    void setUp() {
        repository = mock(CameraRepository.class);
        engine = mock(SurveillanceEngine.class);
        properties = new SentiveillanceProperties();
        service = new CameraService(repository, engine, properties);

        stored = new ArrayList<>();
        long[] nextId = {1};
        when(repository.save(any(Camera.class))).thenAnswer(call -> {
            Camera camera = call.getArgument(0);
            if (camera.getId() == null) {
                camera.setId(nextId[0]++);
                stored.add(camera);
            }
            return camera;
        });
        when(repository.findByUrl(anyString())).thenAnswer(call ->
                stored.stream().filter(c -> c.getUrl().equals(call.getArgument(0))).findFirst());
        when(repository.findByNameIgnoreCase(anyString())).thenAnswer(call ->
                stored.stream().filter(c -> c.getName().equalsIgnoreCase(call.getArgument(0))).findFirst());
        when(repository.findAllByOrderByNameAsc()).thenAnswer(call -> List.copyOf(stored));
        when(repository.findById(any())).thenAnswer(call ->
                stored.stream().filter(c -> c.getId().equals(call.getArgument(0))).findFirst());
    }

    CameraForm form(String name, String url) {
        return new CameraForm(name, url, null, null, null, null, null, null, null);
    }

    @Test
    void savesAUrlOnceAndKeepsNamesDistinct() {
        Camera first = service.save(form("gate", URL));
        assertEquals("gate", first.getName());

        // Same camera added again: updated in place, not duplicated - two rows would both
        // claim to be the live source.
        Camera again = service.save(form("front gate", URL));
        assertEquals(first.getId(), again.getId());
        assertEquals("front gate", again.getName());
        assertEquals(1, stored.size());

        // A different camera asking for a taken name gets a suffix, because the name is what
        // every detection row is stamped with.
        Camera other = service.save(form("front gate", "rtsp://svc:secret@192.168.1.109:554/cam"));
        assertEquals("front gate-2", other.getName());
    }

    @Test
    void buildsTheUrlFromPartsWhenNoUrlIsGiven() {
        Camera camera = service.save(new CameraForm("lobby", "  ", "192.168.1.50", 554,
                "svc", "p@ss", 1, 1, "DoNotUseVlc"));
        assertEquals("rtsp://svc:p%40ss@192.168.1.50:554/cam/realmonitor?channel=1&subtype=1",
                camera.getUrl());
        assertEquals("DoNotUseVlc", camera.getFlags());
    }

    @Test
    void namesACameraAfterItsHostWhenNoNameIsGiven() {
        assertEquals("192.168.1.108", service.save(form(null, URL)).getName());
    }

    @Test
    void refusesBadUrlsBeforeAnythingIsStored() {
        assertThrows(IllegalArgumentException.class, () -> service.save(form("x", "192.168.1.108")));
        assertThrows(IllegalArgumentException.class, () -> service.save(form("x", "file:///tmp/a.mp4")));
        assertThrows(IllegalArgumentException.class,
                () -> service.save(new CameraForm("x", null, null, null, null, null, null, null, null)));
        assertTrue(stored.isEmpty());
    }

    @Test
    void masksThePasswordInWhatTheDashboardSees() {
        service.save(form("gate", URL));
        var view = service.list().getFirst();
        assertEquals("rtsp://***:***@192.168.1.108:554/cam/realmonitor?channel=1&subtype=0", view.url());
        assertFalse(view.url().contains("secret"));
        assertFalse(view.active());
    }

    @Test
    void marksTheCameraThatIsCurrentlyTheLiveSource() {
        Camera gate = service.save(form("gate", URL));
        service.save(form("lobby", "rtsp://svc:secret@192.168.1.109:554/cam"));

        properties.getSource().setType(SourceType.IP_CAMERA);
        properties.getSource().setUrl(URL);

        var views = service.list();
        assertTrue(views.stream().filter(v -> v.id().equals(gate.getId())).allMatch(v -> v.active()));
        assertEquals(1, views.stream().filter(CameraView::active).count());

        // Back on a video file, nothing is the live camera.
        properties.getSource().setType(SourceType.VIDEO_FILE);
        assertEquals(0, service.list().stream().filter(CameraView::active).count());
    }

    @Test
    void willNotDeleteTheCameraThatIsLive() {
        Camera gate = service.save(form("gate", URL));
        properties.getSource().setType(SourceType.IP_CAMERA);
        properties.getSource().setUrl(URL);

        assertThrows(IllegalStateException.class, () -> service.delete(gate.getId()));
        verify(repository, never()).delete(any());

        properties.getSource().setType(SourceType.VIDEO_FILE);
        service.delete(gate.getId());
        verify(repository).delete(gate);
    }

    @Test
    void registersTheConfiguredCameraOnceSoItStaysSelectable() {
        properties.getSource().setType(SourceType.IP_CAMERA);
        properties.getSource().setName("gate-camera");
        properties.getSource().setUrl(URL);

        service.importConfiguredCamera();
        service.importConfiguredCamera();

        assertEquals(1, stored.size());
        assertEquals("gate-camera", stored.getFirst().getName());
        assertEquals(URL, stored.getFirst().getUrl());
    }

    @Test
    void ignoresTheConfiguredSourceWhenItIsAFileOrUnusable() {
        properties.getSource().setType(SourceType.VIDEO_FILE);
        properties.getSource().setFile("my_files/clip.mp4");
        service.importConfiguredCamera();

        properties.getSource().setType(SourceType.IP_CAMERA);
        properties.getSource().setUrl("not a url");
        service.importConfiguredCamera();

        assertTrue(stored.isEmpty());
    }

    @Test
    void requireReportsAMissingCameraAsABadRequest() {
        assertThrows(IllegalArgumentException.class, () -> service.require(42));
        when(repository.findById(7L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.require(7));
    }
}
