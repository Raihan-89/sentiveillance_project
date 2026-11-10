package com.raihan.sentiveillance_project.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A person the engine saw and tracked but could not identify, held so they can be named and
 * enrolled without ever taking a photograph.
 *
 * <p>A usable live template queues the person while visible. Pending templates are matched
 * across visits, and a better face-quality score updates the existing image and template
 * together. Disappearance provides one last capture attempt if early extraction fails.
 *
 * <p>Not {@code @Lob} - see {@link Employee#getFaceTemplate()} for why that mapping breaks on
 * PostgreSQL.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 9 November, 2026
 */
@Entity
@Table(name = "unknown_subject")
@Getter
@Setter
@NoArgsConstructor
public class UnknownSubject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Source the person was seen on. */
    @Column(name = "source_name", nullable = false, length = 200)
    private String sourceName;

    /** The SDK's id for that one visit, useful when correlating with detection_event rows. */
    @Column(name = "trace_index", nullable = false)
    private Integer traceIndex;

    @Column(name = "seen_at", nullable = false)
    private Instant seenAt = Instant.now();

    /** Serialised NLTemplate from the surveillance engine, ready for addTemplate. */
    @Column(name = "face_template", nullable = false, columnDefinition = "bytea")
    private byte[] faceTemplate;

    /** File name of the best-frame JPEG, so the UI can show who this is. */
    @Column(name = "snapshot_name", length = 500)
    private String snapshotName;

    /** SDK face quality (0..100). Null means a capture from before quality tracking. */
    @Column(name = "face_quality")
    private Integer faceQuality;

    /** Set once enrolled, so the row can be kept as an audit trail rather than deleted. */
    @Column(name = "enrolled_employee_id")
    private Long enrolledEmployeeId;
}
