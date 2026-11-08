package com.raihan.sentiveillance_project.sdk;

import com.raihan.sentiveillance_project.domain.Employee;
import com.raihan.sentiveillance_project.enrollment.EnrollmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Startup order, which matters a great deal with this SDK:
 * <ol>
 *   <li>native libraries on the JNA path - already done in {@code main()}</li>
 *   <li>obtain licenses - nothing else in the SDK works first</li>
 *   <li>build face templates from the employee photos</li>
 *   <li>create the engine, load the watch list, attach the source, start capturing</li>
 * </ol>
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 8 November, 2026
 */
@Slf4j
@Component
public class SurveillanceStarter implements ApplicationRunner {

    private final LicenseService licenseService;
    private final EnrollmentService enrollmentService;
    private final SurveillanceEngine engine;

    public SurveillanceStarter(LicenseService licenseService,
                               EnrollmentService enrollmentService,
                               SurveillanceEngine engine) {
        this.licenseService = licenseService;
        this.enrollmentService = enrollmentService;
        this.engine = engine;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("=== SentiVeillance pipeline starting ===");

        licenseService.obtain();

        List<Employee> watchList = enrollmentService.enrollFromPhotoDirectory();
        if (watchList.isEmpty()) {
            log.warn("Watch list is empty - faces will be detected and tracked, "
                    + "but nobody can be identified as an employee");
        }

        engine.start(watchList);
        log.info("=== Pipeline up. Watch the log for SUBJECT_* events ===");
    }
}
