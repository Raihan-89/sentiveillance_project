package com.raihan.sentiveillance_project.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 9 November, 2026
 */
public interface UnknownSubjectRepository extends JpaRepository<UnknownSubject, Long> {

    /** Still waiting to be named, newest first. */
    List<UnknownSubject> findByEnrolledEmployeeIdIsNullOrderBySeenAtDesc(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UnknownSubject s where s.enrolledEmployeeId is null order by s.id")
    List<UnknownSubject> lockPending();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UnknownSubject s where s.id = :id")
    Optional<UnknownSubject> lockById(@Param("id") long id);

    long countByEnrolledEmployeeIdIsNull();
}
