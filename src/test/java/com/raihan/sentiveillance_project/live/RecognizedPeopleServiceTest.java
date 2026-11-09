package com.raihan.sentiveillance_project.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecognizedPeopleServiceTest {
    @Test
    void updatesOneCardPerPersonAndKeepsFaceAndScoreBetweenMatches() {
        var service = new RecognizedPeopleService();
        byte[] face = {1, 2, 3};
        assertTrue(service.needsFace("EMP-1"));
        service.seen("EMP-1", "Ayesha", "video.mp4", 85, face);
        assertFalse(service.needsFace("EMP-1"));
        service.seen("EMP-1", "Ayesha", "video.mp4", null, null);
        assertEquals(1, service.list().size());
        var person = service.list().getFirst();
        assertEquals("Ayesha", person.name());
        assertEquals("video.mp4", person.source());
        assertEquals(85, person.score());
        assertNotNull(person.lastSeen());
        assertArrayEquals(face, service.face("EMP-1"));
    }

    @Test
    void waitsForUsableFaceAndEvictsLeastRecentlySeenPerson() {
        var service = new RecognizedPeopleService();
        service.seen("missing-face", "Name", "source", 80, null);
        assertTrue(service.list().isEmpty());
        for (int i = 0; i < 24; i++) {
            service.seen("EMP-" + i, "Name", "source", 80, new byte[]{1});
        }
        service.seen("EMP-0", "Name", "source", 90, null);
        service.seen("EMP-24", "Name", "source", 80, new byte[]{2});
        assertEquals(24, service.list().size());
        assertNull(service.face("EMP-1"));
        assertNotNull(service.face("EMP-0"));
        service.clear();
        assertTrue(service.list().isEmpty());
        assertNull(service.face("EMP-0"));
        assertTrue(service.needsFace("EMP-0"));
    }
}
