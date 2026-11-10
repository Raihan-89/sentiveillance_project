package com.raihan.sentiveillance_project.records;

import java.time.Instant;

/**
 * A plain-Java copy of one {@code NSurveillanceEventDetails}.
 *
 * @param sourceName           configured name of the source the event came from
 * @param eventType            NSurveillanceEventType name
 * @param traceIndex           per-source tracking id - all events of one visit share it
 * @param eventTime            SDK timestamp for the frame
 * @param frameIndex           frame number within the source
 * @param subjectType          modalities the SDK attributes to this subject
 * @param objectType           NSurveillanceObjectType, e.g. PERSON
 * @param detectionConfidence  detector confidence for the tracked blob
 * @param hasFaceDetails       whether facial details/template were extracted
 * @param matchedTemplateId    best watch-list id, or null if unknown
 * @param matchScore           score of that best match
 * @param rectLeft             bounding box, frame coordinates
 * @param snapshotPath         JPEG written for this event, or null
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 8 November, 2026
 */
public record SubjectEventSnapshot(
        String sourceName,
        String eventType,
        int traceIndex,
        Instant eventTime,
        long frameIndex,
        String subjectType,
        String objectType,
        Float detectionConfidence,
        boolean hasFaceDetails,
        String matchedTemplateId,
        Integer matchScore,
        Integer rectLeft,
        Integer rectTop,
        Integer rectWidth,
        Integer rectHeight,
        String snapshotPath,
        String error
) {
    public boolean isIdentified() {
        return matchedTemplateId != null;
    }
}
