package com.raihan.sentiveillance_project.enrollment;

import com.raihan.sentiveillance_project.domain.UnknownSubject;
import com.raihan.sentiveillance_project.domain.UnknownSubjectRepository;
import com.raihan.sentiveillance_project.config.SentiveillanceProperties;
import com.raihan.sentiveillance_project.live.LiveEventStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;

/** Serial candidate writes; row locks also protect concurrent enrollment and dismissal. */
@Slf4j
@Service
public class UnknownCandidateService {
    private final UnknownSubjectRepository repository;
    private final CandidateFaceMatcher matcher;
    private final SentiveillanceProperties properties;
    private final LiveEventStream events;
    private final java.util.Map<Long, Long> mergedIds = new java.util.concurrent.ConcurrentHashMap<>();

    public record Result(long id, int quality, boolean created) {}

    public UnknownCandidateService(UnknownSubjectRepository repository, CandidateFaceMatcher matcher,
                                   SentiveillanceProperties properties, LiveEventStream events) {
        this.repository = repository;
        this.matcher = matcher;
        this.properties = properties;
        this.events = events;
    }

    @Transactional(rollbackFor = Exception.class)
    public Result capture(String source, int trace, Long previousId, byte[] template, byte[] jpeg, int quality)
            throws IOException {
        var pending = repository.lockPending();
        var samePerson = new ArrayList<UnknownSubject>();
        Long canonicalId = canonicalId(previousId);
        UnknownSubject previous = canonicalId == null ? null : pending.stream()
                .filter(s -> canonicalId.equals(s.getId())).findFirst().orElse(null);
        // A named or dismissed candidate must not be resurrected by an in-flight frame.
        if (previousId != null && previous == null) return null;
        if (previous != null) {
            // The tracker already established identity for this visit. Improvements need
            // no new face comparisons against the rest of the queue.
            samePerson.add(previous);
        } else {
            for (UnknownSubject candidate : pending) {
                if (matcher.matches(template, candidate.getFaceTemplate())) samePerson.add(candidate);
            }
        }
        boolean created = samePerson.isEmpty();
        UnknownSubject target = created ? new UnknownSubject() : samePerson.getFirst();
        // Keep the oldest card/id so typed names survive better captures and duplicate merges.
        for (UnknownSubject duplicate : samePerson) {
            if (duplicate == target) continue;
            if (qualityOf(duplicate) > qualityOf(target)) {
                target.setFaceQuality(duplicate.getFaceQuality());
                target.setFaceTemplate(duplicate.getFaceTemplate());
                target.setSnapshotName(duplicate.getSnapshotName());
            }
            repository.delete(duplicate);
            afterCommit(() -> mergedIds.put(duplicate.getId(), target.getId()));
        }
        boolean better = created || quality > qualityOf(target);
        if (better) {
            Path dir = Path.of(properties.getSnapshots().getDirectory()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path snapshot = Files.createTempFile(dir, "queue_face_", ".jpg");
            try {
                Files.write(snapshot, jpeg);
            } catch (IOException e) {
                Files.deleteIfExists(snapshot);
                throw e;
            }
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            try { Files.deleteIfExists(snapshot); }
                            catch (IOException e) { log.debug("Could not remove rolled-back candidate snapshot", e); }
                        }
                    }
                });
            }
            target.setSourceName(source);
            target.setTraceIndex(trace);
            target.setSeenAt(Instant.now());
            target.setFaceTemplate(template);
            target.setSnapshotName(snapshot.getFileName().toString());
            target.setFaceQuality(quality < 0 ? null : quality);
            repository.save(target);
        }
        if (better || samePerson.size() > 1) notifyAfterCommit();
        return new Result(target.getId(), qualityOf(target), created);
    }

    @Transactional
    public void removeIfPending(long id) {
        repository.lockById(canonicalId(id)).ifPresent(candidate -> {
            if (candidate.getEnrolledEmployeeId() == null) {
                repository.delete(candidate);
                notifyAfterCommit();
            }
        });
    }

    private static int qualityOf(UnknownSubject candidate) {
        return candidate.getFaceQuality() == null ? -1 : candidate.getFaceQuality();
    }

    private Long canonicalId(Long id) {
        while (id != null && mergedIds.containsKey(id)) id = mergedIds.get(id);
        return id;
    }

    private void notifyAfterCommit() {
        afterCommit(events::candidatesChanged);
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }
}
