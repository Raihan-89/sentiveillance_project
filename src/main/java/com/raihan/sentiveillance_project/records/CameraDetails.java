package com.raihan.sentiveillance_project.records;

/**
 * What the SDK can tell us about a connected camera, flattened into plain Java so it can be
 * serialised to the dashboard and kept in the database.
 *
 * @param deviceId     the plugin's own id for the device (NdmMedia gives a URL-derived id)
 * @param displayName  the name we asked for via the {@code DisplayName} parameter
 * @param make         vendor string, if the plugin knows one
 * @param model        model string, if the plugin knows one
 * @param serialNumber serial, if the plugin knows one
 * @param available    the SDK still considers the device usable
 * @param capturing    frames are being pulled right now
 * @param width        frame width in pixels, 0 if the format is not negotiated yet
 * @param height       frame height in pixels, 0 if unknown
 * @param frameRate    frames per second as reported by the stream, null if unknown
 * @param codec        the media subtype, e.g. {@code H264}
 * @param formatCount  how many formats the device offers (one, for a network stream);
 *                     0 when the formats were not read - see {@code describe(camera, false)}
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 10 November, 2026
 */
public record CameraDetails(String deviceId, String displayName, String make, String model,
                            String serialNumber, boolean available, boolean capturing,
                            int width, int height, Double frameRate, String codec,
                            int formatCount) {
}
