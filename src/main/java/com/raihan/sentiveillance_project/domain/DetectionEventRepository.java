package com.raihan.sentiveillance_project.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 8 November, 2026
 */
public interface DetectionEventRepository extends JpaRepository<DetectionEvent, Long> {

    List<DetectionEvent> findByOrderByEventTimeDesc(Pageable pageable);

    long countByEventType(String eventType);
}
