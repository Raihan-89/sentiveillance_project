package com.raihan.sentiveillance_project.sdk;

import com.raihan.sentiveillance_project.records.Parsed;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything the project needs to do with a camera URL that does not involve the SDK:
 * check it, split it, hide the password in it, and build one for a Dahua.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 10 November, 2026
 */
public final class CameraUrls {

    /** scheme://[userinfo@]host[:port][/rest] - deliberately lenient about userinfo. */
    private static final Pattern URL = Pattern.compile(
            "^(?<scheme>[a-zA-Z][a-zA-Z0-9+.\\-]*)://(?:(?<userinfo>[^/?#]*)@)?"
                    + "(?<host>\\[[^\\]]+]|[^:/?#@]+)(?::(?<port>\\d{1,5}))?(?<rest>[/?#].*)?$");

    /** Schemes the SDK's Media plugin can actually open over the network. */
    private static final java.util.Set<String> SCHEMES =
            java.util.Set.of("rtsp", "rtsps", "http", "https", "rtmp", "udp");

    private CameraUrls() {
    }

    public static Parsed parse(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Camera URL is required");
        }

        String trimmed = url.trim();
        Matcher m = URL.matcher(trimmed);

        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "Not a camera URL. Expected something like "
                            + "rtsp://user:pass@192.168.1.108:554/cam/realmonitor?channel=1&subtype=0");
        }

        String scheme = m.group("scheme").toLowerCase(java.util.Locale.ROOT);

        if (!SCHEMES.contains(scheme)) {
            throw new IllegalArgumentException("Unsupported scheme '" + scheme + "'. Use one of " + SCHEMES);
        }

        String host = m.group("host");

        if (host.isBlank()) {
            throw new IllegalArgumentException("Camera URL has no host");
        }

        int port = m.group("port") == null ? defaultPort(scheme) : Integer.parseInt(m.group("port"));

        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port out of range: " + port);
        }

        return new Parsed(trimmed, scheme, host.replace("[", "").replace("]", ""), port);
    }

    private static int defaultPort(String scheme) {
        return switch (scheme) {
            case "rtsp", "rtsps" -> 554;
            case "https" -> 443;
            case "rtmp" -> 1935;
            default -> 80;
        };
    }

    /** Keeps the camera password out of logs, the API and the browser. */
    public static String mask(String url) {
        return url == null ? null : url.replaceAll("://[^/?#]*@", "://***:***@");
    }

    /**
     * The URL for a Dahua (and most ONVIF-ish) camera, as documented for the
     * DH-IPC-HDW2431TP-AS-S2: {@code subtype=0} is the main stream, {@code 1} the sub stream.
     */
    public static String dahua(String host, int port, String username, String password,
                               int channel, int subtype) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Camera host or IP address is required");
        }
        StringBuilder url = new StringBuilder("rtsp://");
        if (username != null && !username.isBlank()) {
            url.append(encodeUserInfo(username));
            if (password != null && !password.isEmpty()) {
                url.append(':').append(encodeUserInfo(password));
            }
            url.append('@');
        }
        url.append(host.trim()).append(':').append(port <= 0 ? 554 : port)
                .append("/cam/realmonitor?channel=").append(channel <= 0 ? 1 : channel)
                .append("&subtype=").append(Math.max(0, subtype));
        return parse(url.toString()).url();
    }

    /**
     * Percent-encodes a username or password so that a password containing {@code @} or
     * {@code :} cannot break the URL apart - the single most common reason a camera that
     * works in its own web UI refuses to open over RTSP.
     */
    static String encodeUserInfo(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            boolean unreserved = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return out.toString();
    }
}
