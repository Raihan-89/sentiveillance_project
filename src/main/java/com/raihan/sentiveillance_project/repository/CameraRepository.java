package com.raihan.sentiveillance_project.repository;

import com.raihan.sentiveillance_project.domain.Camera;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 10 November, 2026
 */
public interface CameraRepository extends JpaRepository<Camera, Long> {

    List<Camera> findAllByOrderByNameAsc();

    Optional<Camera> findByUrl(String url);

    Optional<Camera> findByNameIgnoreCase(String name);
}
