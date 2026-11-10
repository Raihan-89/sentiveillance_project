package com.raihan.sentiveillance_project.camera;

import com.raihan.sentiveillance_project.sdk.CameraUrls;

/**
 * What the dashboard sends when adding or testing a camera.
 *
 * <p>Two ways to say the same thing, because both are useful: paste a full RTSP URL (what you
 * get from the camera's documentation or from VLC), or give the parts and let
 * {@link CameraUrls#dahua} assemble them. The second exists because the Dahua path
 * ({@code /cam/realmonitor?channel=1&subtype=0}) is easy to mistype, and because a password
 * with an {@code @} in it has to be percent-encoded - which is exactly the mistake that makes
 * a camera that works in its own web UI refuse to open over RTSP.
 *
 * @param name     label for the camera; falls back to its host
 * @param url      full stream URL - if set, the fields below are ignored
 * @param host     camera IP or host name
 * @param port     RTSP port, 554 when not given
 * @param username camera account
 * @param password that account's password, percent-encoded on the way into the URL
 * @param channel  Dahua channel number, 1 when not given
 * @param subtype  0 = main stream (full resolution), 1 = sub stream
 * @param flags    optional Media-plugin flags for this camera
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 10 November, 2026
 */
public record CameraForm(String name, String url, String host, Integer port, String username,
                         String password, Integer channel, Integer subtype, String flags) {

    /**
     * @return the URL to hand the SDK
     * @throws IllegalArgumentException if neither a URL nor a host was given, or the result
     *                                  is not a URL the Media plugin can open
     */
    public String resolveUrl() {
        if (url != null && !url.isBlank()) {
            return CameraUrls.parse(url).url();
        }
        return CameraUrls.dahua(host, port == null ? 554 : port, username, password,
                channel == null ? 1 : channel, subtype == null ? 0 : subtype);
    }

    public String trimmedName() {
        return name == null || name.isBlank() ? null : name.trim();
    }

    public String trimmedFlags() {
        return flags == null || flags.isBlank() ? null : flags.trim();
    }
}
