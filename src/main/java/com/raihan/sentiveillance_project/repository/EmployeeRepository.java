package com.raihan.sentiveillance_project.repository;

import com.raihan.sentiveillance_project.domain.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

/**
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 8 November, 2026
 */
public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByTemplateId(String templateId);

    boolean existsByTemplateId(String templateId);

    /** Highest employee number in use, so a person enrolled from video gets the next one. */
    @Query("select max(e.employeeNo) from Employee e")
    Integer findMaxEmployeeNo();
}
