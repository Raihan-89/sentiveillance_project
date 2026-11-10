package com.raihan.sentiveillance_project.records;

import com.raihan.sentiveillance_project.domain.Camera;
import com.raihan.sentiveillance_project.sdk.CameraUrls;

import java.time.Instant;

/**
 * A camera as the dashboard sees it - URL masked, so the camera password never leaves the
 * server even though the row holds it.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 10 November, 2026
 */
public record CameraView(Long id, String name, String url, String flags, boolean active,
                         Instant createdAt, Instant lastSeenAt, Instant lastUsedAt,
                         String lastResult, Boolean lastOk, Integer width, Integer height,
                         Double frameRate, String codec, String make, String model) {

    public static CameraView of(Camera camera, boolean active) {
        return new CameraView(camera.getId(), camera.getName(), CameraUrls.mask(camera.getUrl()),
                camera.getFlags(), active, camera.getCreatedAt(), camera.getLastSeenAt(),
                camera.getLastUsedAt(), camera.getLastResult(), camera.getLastOk(),
                camera.getLastWidth(), camera.getLastHeight(), camera.getLastFrameRate(),
                camera.getLastCodec(), camera.getLastMake(), camera.getLastModel());
    }
}
