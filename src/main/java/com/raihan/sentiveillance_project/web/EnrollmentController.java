package com.raihan.sentiveillance_project.web;

import com.raihan.sentiveillance_project.domain.Employee;
import com.raihan.sentiveillance_project.domain.UnknownSubject;
import com.raihan.sentiveillance_project.domain.UnknownSubjectRepository;
import com.raihan.sentiveillance_project.enrollment.VideoEnrollmentService;
import com.raihan.sentiveillance_project.sdk.SurveillanceEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Review queue for people the camera saw but could not name, and the one call that turns such
 * a person into an enrolled employee.
 *
 * <p>No photograph is involved: the template was captured from the video itself, so enrolling
 * is {@code POST /api/unknown/{id}/enroll} with a name. The template is pushed into the
 * running engine as part of the same request, so the person is recognised the next time they
 * appear rather than after a restart.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 9 November, 2026
 */
@Slf4j
@RestController
@RequestMapping("/api/unknown")
public class EnrollmentController {

    private final UnknownSubjectRepository unknownSubjects;
    private final VideoEnrollmentService videoEnrollment;
    private final SurveillanceEngine engine;

    public EnrollmentController(UnknownSubjectRepository unknownSubjects,
                                VideoEnrollmentService videoEnrollment,
                                SurveillanceEngine engine) {
        this.unknownSubjects = unknownSubjects;
        this.videoEnrollment = videoEnrollment;
        this.engine = engine;
    }

    /** People waiting to be named, newest first. */
    @GetMapping
    public List<Map<String, Object>> pending(@RequestParam(defaultValue = "24") int limit) {
        return unknownSubjects
                .findByEnrolledEmployeeIdIsNullOrderBySeenAtDesc(PageRequest.of(0, Math.min(limit, 200)))
                .stream()
                .map(EnrollmentController::toMap)
                .toList();
    }

    /**
     * Names an unknown subject and enrols them.
     *
     * @param body {@code {"name": "..."}}
     */
    @PostMapping("/{id}/enroll")
    public ResponseEntity<?> enroll(@PathVariable long id, @RequestBody Map<String, String> body) {
        String name = body == null ? null : body.get("name");
        try {
            Employee saved = videoEnrollment.enroll(id, name, (templateId, template) -> {
                engine.rememberWatchListName(templateId, name.trim());
                engine.addToWatchList(templateId, template);
            });
            return ResponseEntity.ok(Map.of(
                    "employeeNo", saved.getEmployeeNo(),
                    "name", saved.getFullName(),
                    "templateId", saved.getTemplateId(),
                    "templateBytes", saved.getFaceTemplate().length,
                    "liveWatchList", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /** Discards a candidate - a duplicate, a passer-by, or a poor capture. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> dismiss(@PathVariable long id) {
        videoEnrollment.dismiss(id);
        return ResponseEntity.ok(Map.of("dismissed", id));
    }

    private static Map<String, Object> toMap(UnknownSubject s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("source", s.getSourceName());
        m.put("traceIndex", s.getTraceIndex());
        m.put("seenAt", s.getSeenAt());
        m.put("templateBytes", s.getFaceTemplate().length);
        m.put("snapshot", s.getSnapshotName());
        m.put("faceQuality", s.getFaceQuality());
        return m;
    }
}
