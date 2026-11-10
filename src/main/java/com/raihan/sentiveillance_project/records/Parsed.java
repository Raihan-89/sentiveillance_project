package com.raihan.sentiveillance_project.records;

/**
 * @param url      the URL as it should be handed to the SDK
 * @param scheme   lower-cased scheme
 * @param host     host name or IP, brackets stripped from IPv6
 * @param port     explicit port, or the default for the scheme
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 10 November, 2026
 */
public record Parsed(String url, String scheme, String host, int port) {
}
