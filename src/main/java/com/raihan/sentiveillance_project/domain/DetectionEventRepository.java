package com.raihan.sentiveillance_project.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DetectionEventRepository extends JpaRepository<DetectionEvent, Long> {

    List<DetectionEvent> findByOrderByEventTimeDesc(Pageable pageable);

    long countByEventType(String eventType);
}
