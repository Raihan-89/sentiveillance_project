package com.raihan.sentiveillance_project.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One surveillance event as reported by the SDK.
 *
 *  @author Md. Raihan Shikder (Raihan-89)
 *  @date 8 November, 2026
 */
@Entity
@Table(name = "detection_event", indexes = {
        @Index(name = "ix_detection_trace", columnList = "source_name,trace_index"),
        @Index(name = "ix_detection_time", columnList = "event_time")
})
@Getter
@Setter
@NoArgsConstructor
public class DetectionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_name", nullable = false, length = 200)
    private String sourceName;

    /** NSurveillanceEventType: SUBJECT_APPEARED / SUBJECT_TRACK / SUBJECT_DISAPPEARED / ... */
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    /** Stable per-subject tracking id within this source, from NSurveillanceEventDetails.getTraceIndex(). */
    @Column(name = "trace_index", nullable = false)
    private Integer traceIndex;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    @Column(name = "frame_index")
    private Long frameIndex;

    /** What the SDK says this subject is: FACES, VEHICLES_AND_HUMANS, ... */
    @Column(name = "subject_type", length = 100)
    private String subjectType;

    /** NSurveillanceObjectType of the tracked blob, e.g. PERSON. */
    @Column(name = "object_type", length = 40)
    private String objectType;

    @Column(name = "detection_confidence")
    private Float detectionConfidence;

    /** True when a face template could be extracted for this subject. */
    @Column(name = "has_face_details", nullable = false)
    private boolean hasFaceDetails;

    /** Best watch-list match id, or null when the subject is unknown. */
    @Column(name = "matched_template_id", length = 200)
    private String matchedTemplateId;

    @Column(name = "match_score")
    private Integer matchScore;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @Column(name = "rect_left")
    private Integer rectLeft;

    @Column(name = "rect_top")
    private Integer rectTop;

    @Column(name = "rect_width")
    private Integer rectWidth;

    @Column(name = "rect_height")
    private Integer rectHeight;

    @Column(name = "snapshot_path", length = 1000)
    private String snapshotPath;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
