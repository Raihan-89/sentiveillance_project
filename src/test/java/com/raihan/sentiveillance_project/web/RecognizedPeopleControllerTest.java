package com.raihan.sentiveillance_project.web;

import com.raihan.sentiveillance_project.config.SentiveillanceProperties;
import com.raihan.sentiveillance_project.controller.LiveController;
import com.raihan.sentiveillance_project.service.RecognizedPeopleService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.*;

class RecognizedPeopleControllerTest {
    @Test
    void servesFaceAndMetadataWithoutCachingAndReturns404AfterClear() {
        var people = new RecognizedPeopleService();
        var controller = new LiveController(null, null, new SentiveillanceProperties(), people);
        byte[] jpeg = {1, 2, 3};
        people.seen("EMP-7", "Raihan", "clip.mp4", 92, jpeg);
        var list = controller.recognized();
        assertEquals("no-store", list.getHeaders().getCacheControl());
        assertEquals("Raihan", list.getBody().getFirst().name());
        var face = controller.recognizedFace("EMP-7");
        assertEquals(200, face.getStatusCode().value());
        assertEquals(MediaType.IMAGE_JPEG, face.getHeaders().getContentType());
        assertEquals("no-store", face.getHeaders().getCacheControl());
        assertArrayEquals(jpeg, face.getBody());
        assertEquals(404, controller.recognizedFace("missing").getStatusCode().value());
        people.clear();
        assertEquals(404, controller.recognizedFace("EMP-7").getStatusCode().value());
        assertTrue(controller.recognized().getBody().isEmpty());
    }
}
