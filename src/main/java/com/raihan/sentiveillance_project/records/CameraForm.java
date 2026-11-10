package com.raihan.sentiveillance_project.records;

import com.raihan.sentiveillance_project.sdk.CameraUrls;

/**
 * What the dashboard sends when adding or testing a camera.
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
