package com.raihan.sentiveillance_project.live;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, process-local face previews; independent of enrollment and persisted snapshots. */
@Service
public class RecognizedPeopleService {
    private static final int LIMIT = 24;
    private static final long REFRESH_MILLIS = 5_000;
    private final Map<String, Entry> people = new LinkedHashMap<>();

    public synchronized boolean needsFace(String id) {
        Entry entry = people.get(id);
        return entry == null || System.currentTimeMillis() - entry.faceVersion >= REFRESH_MILLIS;
    }

    public synchronized void seen(String id, String name, String source, Integer score, byte[] face) {
        Entry old = people.remove(id);
        if (old == null && face == null) return;
        long now = System.currentTimeMillis();
        people.put(id, new Entry(id, name, source, score == null && old != null ? old.score : score,
                Instant.now(), face == null ? old.faceVersion : now, face == null ? old.face : face));
        while (people.size() > LIMIT) people.remove(people.keySet().iterator().next());
    }

    public synchronized List<Person> list() {
        return people.values().stream().sorted(Comparator.comparing(Entry::lastSeen).reversed())
                .map(e -> new Person(e.id, e.name, e.source, e.score, e.lastSeen, e.faceVersion)).toList();
    }

    public synchronized byte[] face(String id) {
        Entry entry = people.get(id);
        return entry == null ? null : entry.face;
    }

    public synchronized void clear() {
        people.clear();
    }

    public record Person(String templateId, String name, String source, Integer score,
                         Instant lastSeen, long faceVersion) {}
    private record Entry(String id, String name, String source, Integer score, Instant lastSeen,
                         long faceVersion, byte[] face) {}
}
