package com.raihan.sentiveillance_project.sdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 10 November, 2026
 */
class CameraUrlsTest {

    @Test
    void parsesRtspWithCredentialsQueryAndDefaultPort() {
        var full = CameraUrls.parse("rtsp://svc:secret@192.168.1.108:554/cam/realmonitor?channel=1&subtype=0");
        assertEquals("rtsp", full.scheme());
        assertEquals("192.168.1.108", full.host());
        assertEquals(554, full.port());

        assertEquals(554, CameraUrls.parse("rtsp://192.168.1.108/stream").port());
        assertEquals(80, CameraUrls.parse("http://cam.local/video.cgi").port());
        assertEquals(443, CameraUrls.parse("https://cam.local/video.cgi").port());
        assertEquals("rtsp://cam/x", CameraUrls.parse("  rtsp://cam/x  ").url());
    }

    @Test
    void acceptsAPasswordContainingAnAtSign() {
        // The host must be read from the LAST '@', or 'pa' becomes the host.
        var parsed = CameraUrls.parse("rtsp://svc:pa@ss@192.168.1.108:554/cam");
        assertEquals("192.168.1.108", parsed.host());
    }

    @Test
    void rejectsWhatThePluginCannotOpen() {
        assertThrows(IllegalArgumentException.class, () -> CameraUrls.parse(null));
        assertThrows(IllegalArgumentException.class, () -> CameraUrls.parse("  "));
        assertThrows(IllegalArgumentException.class, () -> CameraUrls.parse("192.168.1.108"));
        assertThrows(IllegalArgumentException.class, () -> CameraUrls.parse("file:///tmp/clip.mp4"));
        assertThrows(IllegalArgumentException.class, () -> CameraUrls.parse("rtsp://host:99999/x"));
    }

    @Test
    void masksTheCameraPassword() {
        assertEquals("rtsp://***:***@192.168.1.108:554/cam",
                CameraUrls.mask("rtsp://svc:secret@192.168.1.108:554/cam"));
        assertEquals("rtsp://192.168.1.108:554/cam", CameraUrls.mask("rtsp://192.168.1.108:554/cam"));
        // A password with '@' in it must be masked whole, not up to its first '@'.
        assertEquals("rtsp://***:***@192.168.1.108/cam", CameraUrls.mask("rtsp://svc:pa@ss@192.168.1.108/cam"));
        assertNull(CameraUrls.mask(null));
    }

    @Test
    void buildsTheDahuaUrlAndEncodesCredentials() {
        assertEquals("rtsp://svc:secret@192.168.1.108:554/cam/realmonitor?channel=1&subtype=0",
                CameraUrls.dahua("192.168.1.108", 554, "svc", "secret", 1, 0));
        // Defaults fill in rather than producing a URL the plugin would reject.
        assertEquals("rtsp://192.168.1.108:554/cam/realmonitor?channel=1&subtype=0",
                CameraUrls.dahua(" 192.168.1.108 ", 0, "", null, 0, 0));
        // A password with '@' or ':' in it must not split the URL apart.
        assertEquals("rtsp://svc:p%40ss%3Aword@cam:554/cam/realmonitor?channel=2&subtype=1",
                CameraUrls.dahua("cam", 554, "svc", "p@ss:word", 2, 1));
        assertThrows(IllegalArgumentException.class, () -> CameraUrls.dahua(" ", 554, "a", "b", 1, 0));
    }
}
