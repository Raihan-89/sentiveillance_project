package com.raihan.sentiveillance_project.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One enrolled person plus the face template the surveillance engine matches against.
 *
 * <p>{@code templateId} is the string handed to {@code NSurveillance.addTemplate(id, bytes)};
 * it is what comes back in {@code NSEDMatchResult.getId()}, so it is the join key between
 * the SDK's watch list and this table.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 8 November, 2026
 */
@Entity
@Table(name = "employee", uniqueConstraints = @UniqueConstraint(columnNames = "template_id"))
@Getter
@Setter
@NoArgsConstructor
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The number from the photo file name, e.g. 1 for Employee_1_John.png. */
    @Column(name = "employee_no", nullable = false)
    private Integer employeeNo;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    /** Watch-list id passed to NSurveillance.addTemplate. */
    @Column(name = "template_id", nullable = false, length = 200)
    private String templateId;

    @Column(name = "photo_path", length = 1000)
    private String photoPath;

    @Column(name = "face_template", nullable = false, columnDefinition = "bytea")
    private byte[] faceTemplate;

    @Column(name = "enrolled_at", nullable = false)
    private Instant enrolledAt = Instant.now();
}
