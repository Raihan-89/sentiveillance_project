package com.raihan.sentiveillance_project.service;

import com.raihan.sentiveillance_project.domain.Employee;
import com.raihan.sentiveillance_project.repository.EmployeeRepository;
import com.raihan.sentiveillance_project.domain.UnknownSubject;
import com.raihan.sentiveillance_project.repository.UnknownSubjectRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.BiConsumer;

/**
 * Turns a person the camera saw into an enrolled employee, with no photograph involved.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 9 November, 2026
 */
@Slf4j
@Service
public class VideoEnrollmentService {

    private final UnknownSubjectRepository unknownSubjects;
    private final EmployeeRepository employees;

    public VideoEnrollmentService(UnknownSubjectRepository unknownSubjects, EmployeeRepository employees) {
        this.unknownSubjects = unknownSubjects;
        this.employees = employees;
    }

    /**
     * Names an unknown subject and enrols them.
     *
     * @param unknownId        row captured when the person was last seen
     * @param fullName         what to call them
     * @param liveWatchListAdd called with (templateId, template) so the caller can push the
     *                         template into the running engine; invoked only after the row is
     *                         safely stored
     * @return the new employee
     */
    @Transactional
    public Employee enroll(long unknownId, String fullName, BiConsumer<String, byte[]> liveWatchListAdd) {
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("A name is required");
        }

        UnknownSubject subject = unknownSubjects.lockById(unknownId)
                .orElseThrow(() -> new IllegalArgumentException("No unknown subject " + unknownId));

        if (subject.getEnrolledEmployeeId() != null) {
            throw new IllegalStateException("Already enrolled as employee " + subject.getEnrolledEmployeeId());
        }

        // Allocate the next free number, then skip any template id already taken - rows can be
        // deleted by hand, so max+1 is not automatically free.
        int employeeNo = java.util.Objects.requireNonNullElse(employees.findMaxEmployeeNo(), 0) + 1;

        while (employees.existsByTemplateId("EMP-" + employeeNo)) {
            employeeNo++;
        }

        Employee employee = new Employee();
        employee.setEmployeeNo(employeeNo);
        employee.setFullName(fullName.trim());
        employee.setTemplateId("EMP-" + employeeNo);
        employee.setPhotoPath(subject.getSnapshotName() == null ? null : "snapshot:" + subject.getSnapshotName());
        employee.setFaceTemplate(subject.getFaceTemplate());
        Employee saved = employees.save(employee);

        // Keep the row as an audit trail of where this template came from.
        subject.setEnrolledEmployeeId(saved.getId());
        unknownSubjects.save(subject);

        // Only now make it live: if the transaction had rolled back, the engine would be
        // holding a template with no matching row.
        liveWatchListAdd.accept(saved.getTemplateId(), saved.getFaceTemplate());

        log.info("Enrolled {} as {} from a live capture (trace {} on {}, {} byte template)",
                saved.getFullName(), saved.getTemplateId(), subject.getTraceIndex(),
                subject.getSourceName(), saved.getFaceTemplate().length);

        return saved;
    }

    /** Drops a candidate without enrolling - a duplicate, a passer-by, a bad capture. */
    @Transactional
    public void dismiss(long unknownId) {
        unknownSubjects.lockById(unknownId).ifPresent(unknownSubjects::delete);
    }
}
