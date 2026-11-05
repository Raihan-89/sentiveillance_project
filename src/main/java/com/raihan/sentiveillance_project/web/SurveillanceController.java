package com.raihan.sentiveillance_project.web;

import com.raihan.sentiveillance_project.domain.DetectionEvent;
import com.raihan.sentiveillance_project.domain.DetectionEventRepository;
import com.raihan.sentiveillance_project.domain.Employee;
import com.raihan.sentiveillance_project.domain.EmployeeRepository;
import com.raihan.sentiveillance_project.sdk.SurveillanceEngine;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Minimal read/control surface, so progress can be checked without reading the log. */
@RestController
@RequestMapping("/api")
public class SurveillanceController {

    private final SurveillanceEngine engine;
    private final DetectionEventRepository detectionEvents;
    private final EmployeeRepository employees;

    public SurveillanceController(SurveillanceEngine engine,
                                  DetectionEventRepository detectionEvents,
                                  EmployeeRepository employees) {
        this.engine = engine;
        this.detectionEvents = detectionEvents;
        this.employees = employees;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        SurveillanceEngine.Stats stats = engine.stats();
        return Map.of(
                "running", stats.running(),
                "events", stats.events(),
                "subjects", stats.subjects(),
                "identified", stats.identified(),
                "watchListSize", stats.watchListSize(),
                "storedEvents", detectionEvents.count(),
                "employees", employees.count());
    }

    @GetMapping("/employees")
    public List<Map<String, Object>> employees() {
        return employees.findAll().stream()
                .map(e -> Map.<String, Object>of(
                        "employeeNo", e.getEmployeeNo(),
                        "name", e.getFullName(),
                        "templateId", e.getTemplateId(),
                        "templateBytes", e.getFaceTemplate().length,
                        "enrolledAt", e.getEnrolledAt()))
                .toList();
    }

    @GetMapping("/detections")
    public List<Map<String, Object>> detections(@RequestParam(defaultValue = "50") int limit) {
        List<DetectionEvent> rows = detectionEvents.findByOrderByEventTimeDesc(PageRequest.of(0, Math.min(limit, 500)));
        return rows.stream().map(SurveillanceController::toMap).toList();
    }

    @PostMapping("/surveillance/start")
    public Map<String, Object> start() {
        engine.startSource();
        return Map.of("running", engine.isRunning());
    }

    @PostMapping("/surveillance/stop")
    public Map<String, Object> stop() {
        engine.stopSource();
        return Map.of("running", engine.isRunning());
    }

    private static Map<String, Object> toMap(DetectionEvent e) {
        Employee employee = e.getEmployee();
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("source", e.getSourceName());
        map.put("eventType", e.getEventType());
        map.put("traceIndex", e.getTraceIndex());
        map.put("eventTime", e.getEventTime());
        map.put("hasFaceDetails", e.isHasFaceDetails());
        map.put("objectType", e.getObjectType());
        map.put("matchedTemplateId", e.getMatchedTemplateId());
        map.put("matchScore", e.getMatchScore());
        map.put("employee", employee == null ? null : employee.getFullName());
        map.put("snapshotPath", e.getSnapshotPath());
        return map;
    }
}
