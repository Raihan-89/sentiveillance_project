package com.raihan.sentiveillance_project.detection;

import com.raihan.sentiveillance_project.domain.DetectionEvent;
import com.raihan.sentiveillance_project.domain.DetectionEventRepository;
import com.raihan.sentiveillance_project.domain.Employee;
import com.raihan.sentiveillance_project.domain.EmployeeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes {@link SubjectEventSnapshot}s to PostgreSQL and logs them readably.
 *
 * <p>Runs on the engine's own executor, never on an SDK callback thread.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 8 November, 2026
 */
@Slf4j
@Service
public class DetectionRecorder {

    private final DetectionEventRepository detectionEvents;
    private final EmployeeRepository employees;

    /**
     * Persistence only. The dashboard is fed straight from the SDK callback in
     * {@code SurveillanceEngine}, so the overlay never waits on a database insert.
     */
    public DetectionRecorder(DetectionEventRepository detectionEvents, EmployeeRepository employees) {
        this.detectionEvents = detectionEvents;
        this.employees = employees;
    }

    @Transactional
    public void record(SubjectEventSnapshot snapshot) {
        if (snapshot.error() != null) {
            log.error("[{}] source error: {}", snapshot.sourceName(), snapshot.error());
            return;
        }

        DetectionEvent event = new DetectionEvent();
        event.setSourceName(snapshot.sourceName());
        event.setEventType(snapshot.eventType());
        event.setTraceIndex(snapshot.traceIndex());
        event.setEventTime(snapshot.eventTime());
        event.setFrameIndex(snapshot.frameIndex());
        event.setSubjectType(snapshot.subjectType());
        event.setObjectType(snapshot.objectType());
        event.setDetectionConfidence(snapshot.detectionConfidence());
        event.setHasFaceDetails(snapshot.hasFaceDetails());
        event.setMatchedTemplateId(snapshot.matchedTemplateId());
        event.setMatchScore(snapshot.matchScore());
        event.setRectLeft(snapshot.rectLeft());
        event.setRectTop(snapshot.rectTop());
        event.setRectWidth(snapshot.rectWidth());
        event.setRectHeight(snapshot.rectHeight());
        event.setSnapshotPath(snapshot.snapshotPath());

        Employee employee = null;
        if (snapshot.matchedTemplateId() != null) {
            employee = employees.findByTemplateId(snapshot.matchedTemplateId()).orElse(null);
            event.setEmployee(employee);
        }

        detectionEvents.save(event);

        String who = employee != null
                ? employee.getFullName() + " (#" + employee.getEmployeeNo() + ")"
                : snapshot.matchedTemplateId() != null ? snapshot.matchedTemplateId() : "UNKNOWN";

        log.info("[{}] {} trace={} {} score={} face={} box={}x{} frame={}",
                snapshot.sourceName(),
                snapshot.eventType(),
                snapshot.traceIndex(),
                who,
                snapshot.matchScore(),
                snapshot.hasFaceDetails(),
                snapshot.rectWidth(), snapshot.rectHeight(),
                snapshot.frameIndex());
    }
}
