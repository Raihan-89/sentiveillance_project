package com.raihan.sentiveillance_project.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 9 November, 2026
 */
public interface UnknownSubjectRepository extends JpaRepository<UnknownSubject, Long> {

    /** Still waiting to be named, newest first. */
    List<UnknownSubject> findByEnrolledEmployeeIdIsNullOrderBySeenAtDesc(Pageable pageable);

    long countByEnrolledEmployeeIdIsNull();
}
