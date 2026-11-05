package com.raihan.sentiveillance_project.enrollment;

import com.neurotec.biometrics.NBiometricEngine;
import com.neurotec.biometrics.NBiometricStatus;
import com.neurotec.biometrics.NFace;
import com.neurotec.biometrics.NSubject;
import com.neurotec.biometrics.NTemplateSize;
import com.neurotec.images.NImage;
import com.raihan.sentiveillance_project.config.SentiveillanceProperties;
import com.raihan.sentiveillance_project.domain.Employee;
import com.raihan.sentiveillance_project.domain.EmployeeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns employee photos into face templates and stores them.
 *
 * <p>The chain is: {@link NImage} -> {@link NFace} -> {@link NSubject} ->
 * {@code NBiometricEngine.enroll(subject, false)} -> {@code subject.getTemplate().save()}.
 * Those bytes are the <em>template</em>: a mathematical description of the face, not a
 * picture. The surveillance engine only ever compares templates, which is why enrolment can
 * happen offline from a still photo while recognition happens live on video.
 *
 * <p>{@code NBiometricEngine} (rather than {@code NBiometricClient}) is what the SDK's own
 * Java surveillance samples use for watch-list enrolment - see
 * {@code Samples/Surveillance/Java/surveillance-web-sample/.../WatchListService.java}.
 */
@Slf4j
@Service
public class EnrollmentService {

    /** Employee_1_John.png -> no=1, name=John */
    private static final Pattern PHOTO_NAME = Pattern.compile(
            "^Employee_(\\d+)_(.+)$", Pattern.CASE_INSENSITIVE);

    private static final List<String> IMAGE_EXTENSIONS = List.of(".png", ".jpg", ".jpeg", ".bmp", ".tif", ".tiff");

    private final SentiveillanceProperties properties;
    private final EmployeeRepository employees;

    public EnrollmentService(SentiveillanceProperties properties, EmployeeRepository employees) {
        this.properties = properties;
        this.employees = employees;
    }

    /**
     * Enrolls every {@code Employee_<no>_<name>.<ext>} photo in the configured directory.
     *
     * @return the employees that are now enrolled (newly created ones plus pre-existing)
     */
    public List<Employee> enrollFromPhotoDirectory() {
        SentiveillanceProperties.Enrollment cfg = properties.getEnrollment();
        if (!cfg.isEnabled()) {
            log.info("Photo enrolment disabled; using whatever is already in the database");
            return employees.findAll();
        }

        Path dir = Path.of(cfg.getPhotosDirectory()).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            log.warn("Enrolment directory does not exist: {}", dir);
            return employees.findAll();
        }

        List<Path> photos = listPhotos(dir);
        log.info("Found {} candidate photo(s) in {}", photos.size(), dir);

        // One engine for the whole batch. LARGE templates are the most accurate and are what
        // the SDK recommends for watch lists; the extra size is irrelevant for a few hundred
        // employees.
        NBiometricEngine engine = new NBiometricEngine();
        try {
            engine.setFacesTemplateSize(NTemplateSize.LARGE);
            engine.setFacesDetectAllFeaturePoints(true);

            List<Employee> enrolled = new ArrayList<>();
            for (Path photo : photos) {
                try {
                    enrollOne(engine, photo, cfg.isReEnrollExisting()).ifPresent(enrolled::add);
                } catch (Exception e) {
                    log.error("Enrolment failed for {}: {}", photo.getFileName(), e.toString());
                }
            }

            List<Employee> all = employees.findAll();
            log.info("Enrolment finished: {} new/updated, {} employee(s) in the watch list",
                    enrolled.size(), all.size());
            return all;
        } finally {
            engine.dispose();
        }
    }

    @Transactional
    protected java.util.Optional<Employee> enrollOne(NBiometricEngine engine, Path photo, boolean reEnroll) {
        String baseName = stripExtension(photo.getFileName().toString());
        Matcher matcher = PHOTO_NAME.matcher(baseName);
        if (!matcher.matches()) {
            log.warn("Skipping {} - name does not match Employee_<number>_<name>", photo.getFileName());
            return java.util.Optional.empty();
        }

        int employeeNo = Integer.parseInt(matcher.group(1));
        String fullName = matcher.group(2).replace('_', ' ').trim();
        // The watch-list id is what comes back from the matcher, so make it self-describing.
        String templateId = "EMP-" + employeeNo;

        Employee existing = employees.findByTemplateId(templateId).orElse(null);
        if (existing != null && !reEnroll) {
            log.info("Already enrolled: {} -> {}", templateId, existing.getFullName());
            return java.util.Optional.empty();
        }

        NSubject subject = new NSubject();
        NFace face = new NFace();
        NImage image = null;
        try {
            image = NImage.fromFile(photo.toString());
            face.setImage(image);
            subject.setId(templateId);
            subject.getFaces().add(face);

            // enroll() = detect the face, extract its features, build the template.
            // The false flag means "do not persist into the engine's own database" - we keep
            // the bytes ourselves and feed them to NSurveillance.
            NBiometricStatus status = engine.enroll(subject, false);
            if (status != NBiometricStatus.OK) {
                log.error("Could not build a template from {}: {}", photo.getFileName(), status);
                return java.util.Optional.empty();
            }

            byte[] templateBytes = subject.getTemplate().save().toByteArray();

            Employee employee = existing != null ? existing : new Employee();
            employee.setEmployeeNo(employeeNo);
            employee.setFullName(fullName);
            employee.setTemplateId(templateId);
            employee.setPhotoPath(photo.toString());
            employee.setFaceTemplate(templateBytes);
            Employee saved = employees.save(employee);

            log.info("Enrolled {} as {} ({} byte template) from {}",
                    fullName, templateId, templateBytes.length, photo.getFileName());
            return java.util.Optional.of(saved);
        } catch (IOException e) {
            log.error("Could not read image {}: {}", photo, e.getMessage());
            return java.util.Optional.empty();
        } finally {
            // Native handles - release them explicitly, the JVM garbage collector knows
            // nothing about the memory they hold.
            face.dispose();
            subject.dispose();
            if (image != null) {
                image.dispose();
            }
        }
    }

    private List<Path> listPhotos(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> hasImageExtension(p.getFileName().toString()))
                    .filter(p -> PHOTO_NAME.matcher(stripExtension(p.getFileName().toString())).matches())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.error("Could not list {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    private static boolean hasImageExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return IMAGE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
