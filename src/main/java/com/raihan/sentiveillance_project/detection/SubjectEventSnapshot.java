package com.raihan.sentiveillance_project.detection;

import java.time.Instant;

/**
 * A plain-Java copy of one {@code NSurveillanceEventDetails}.
 *
 * <p>Event details are native handles owned by the SDK: they are valid only inside the
 * callback and must be {@code dispose()}d there. Everything we want to keep is therefore
 * copied into this record on the callback thread, and only the record travels to the
 * database executor. Doing real work (JDBC, disk) on an SDK callback thread stalls the
 * video pipeline and drops frames.
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
