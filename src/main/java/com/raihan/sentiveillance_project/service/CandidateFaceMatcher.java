package com.raihan.sentiveillance_project.service;

import com.neurotec.biometrics.*;
import com.neurotec.images.NImage;
import com.raihan.sentiveillance_project.config.SentiveillanceProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;

/** A reusable engine, accessed only by the candidate worker (never the camera callback). */
@Service
public class CandidateFaceMatcher {
    private final SentiveillanceProperties properties;
    private NBiometricEngine engine;

    public CandidateFaceMatcher(SentiveillanceProperties properties) {
        this.properties = properties;
    }

    private NBiometricEngine engine() {
        if (engine == null) {
            engine = new NBiometricEngine();
            engine.setFacesTemplateSize(NTemplateSize.valueOf(properties.getFaces().getTemplateSize()));
            engine.setFacesQualityThreshold((byte) properties.getFaces().getQualityThreshold());
            engine.setFacesDetectAllFeaturePoints(true);
            engine.setMatchingThreshold(Math.max(48, properties.getFaces().getMatchingThreshold()));
        }
        return engine;
    }

    public synchronized byte[] extract(byte[] jpeg) {
        try (NSubject subject = new NSubject(); NFace face = new NFace();
             NImage image = NImage.fromMemory(ByteBuffer.wrap(jpeg))) {
            subject.setId("live-candidate");
            face.setImage(image);
            subject.getFaces().add(face);
            if (engine().createTemplate(subject) != NBiometricStatus.OK) return null;
            // Use the same NLTemplate format as the surveillance callbacks.
            try (NTemplate template = subject.getTemplate(); NLTemplate faces = template.getFaces();
                 var buffer = faces.save()) {
                return buffer.toByteArray();
            }
        }
    }

    public synchronized boolean matches(byte[] first, byte[] second) {
        try (NSubject probe = subject(first); NSubject reference = subject(second)) {
            return engine().verify(probe, reference) == NBiometricStatus.OK;
        }
    }

    private NSubject subject(byte[] bytes) {
        NSubject subject = new NSubject();
        try {
            // Earlier versions saved both complete NTemplate and face-only NLTemplate data.
            try (NLTemplate faces = new NLTemplate(ByteBuffer.wrap(bytes)); NTemplate template = new NTemplate()) {
                template.setFaces(faces);
                subject.setTemplate(template);
            } catch (RuntimeException legacyFormat) {
                try (NTemplate template = new NTemplate(ByteBuffer.wrap(bytes))) {
                    subject.setTemplate(template);
                }
            }
            return subject;
        } catch (RuntimeException | Error e) {
            subject.dispose();
            throw e;
        }
    }

    @PreDestroy
    public synchronized void close() {
        if (engine != null) {
            engine.dispose();
            engine = null;
        }
    }
}
