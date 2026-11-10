package com.raihan.sentiveillance_project.web;

import com.raihan.sentiveillance_project.controller.CameraController;
import com.raihan.sentiveillance_project.records.CameraForm;
import com.raihan.sentiveillance_project.service.CameraService;
import com.raihan.sentiveillance_project.domain.Camera;
import com.raihan.sentiveillance_project.records.CameraDetails;
import com.raihan.sentiveillance_project.sdk.SurveillanceEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 10 November, 2026
 */
class CameraControllerTest {

    static final String URL = "rtsp://svc:secret@192.168.1.108:554/cam/realmonitor?channel=1&subtype=0";

    CameraService cameras;
    SurveillanceEngine engine;
    CameraController controller;

    @BeforeEach
    void setUp() {
        cameras = mock(CameraService.class);
        engine = mock(SurveillanceEngine.class);
        controller = new CameraController(cameras, engine);
    }

    CameraForm form() {
        return new CameraForm("gate", URL, null, null, null, null, null, null, null);
    }

    Camera saved() {
        Camera camera = new Camera();
        camera.setId(1L);
        camera.setName("gate");
        camera.setUrl(URL);
        return camera;
    }

    @Test
    void addDoesNotActivateUnlessAsked() {
        when(cameras.save(any())).thenReturn(saved());
        Map<String, Object> body = controller.add(form(), false);

        assertEquals("rtsp://***:***@192.168.1.108:554/cam/realmonitor?channel=1&subtype=0", body.get("url"));
        assertFalse(body.containsKey("current"));
        verify(cameras, never()).activate(anyLong());
    }

    @Test
    void addWithActivateSwitchesTheLiveSource() {
        when(cameras.save(any())).thenReturn(saved());
        when(cameras.activate(1L)).thenReturn("gate");
        when(engine.isRunning()).thenReturn(true);

        Map<String, Object> body = controller.add(form(), true);
        assertEquals("gate", body.get("current"));
        assertEquals(true, body.get("running"));
        verify(cameras).activate(1L);
    }

    @Test
    void testReportsWhatAnsweredWithThePasswordHidden() {
        var details = new CameraDetails("media:1", "gate", "Dahua", "IPC-HDW2431", null,
                true, false, 2688, 1520, 25.0, "H264", 1);
        when(cameras.test(any(CameraForm.class))).thenReturn(details);

        Map<String, Object> body = controller.test(form());
        assertEquals(details, body.get("camera"));
        assertEquals(true, body.get("reachable"));
        assertFalse(String.valueOf(body.get("url")).contains("secret"));
    }

    /** Every failure here is operator-fixable, so none of them should surface as a 500. */
    @Test
    void mapsFailuresToStatusesTheDashboardCanExplain() {
        assertEquals(400, controller.badRequest(new IllegalArgumentException("bad url")).getStatusCode().value());
        assertEquals(409, controller.conflict(new IllegalStateException("not reachable")).getStatusCode().value());
        assertEquals(502, controller.sdkFailure(new RuntimeException("NError -7")).getStatusCode().value());
        // A native error with no message must still produce something readable.
        assertNotNull(((Map<?, ?>) controller.sdkFailure(new RuntimeException()).getBody()).get("error"));
    }
}
