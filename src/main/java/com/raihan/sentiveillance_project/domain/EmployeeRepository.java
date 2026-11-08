package com.raihan.sentiveillance_project.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 8 November, 2026
 */
public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByTemplateId(String templateId);

    boolean existsByTemplateId(String templateId);
}
