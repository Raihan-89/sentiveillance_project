package com.raihan.sentiveillance_project.records;

/**
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 10 November, 2026
 */
public record Stats(boolean running, long events, long subjects, long identified, int watchListSize,
                    long nonHumanIgnored, long detectionLagMillis, long unknownCaptured) {
}
