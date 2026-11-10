package com.raihan.sentiveillance_project.enums;

/**
 * What kind of input the engine is reading.
 *
 * <p>Both go through the same door in the SDK - the {@code Media} device plugin - and differ
 * only in which parameter carries the address, which is why switching between them costs
 * nothing downstream.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 10 November, 2026
 */
public enum SourceType {
    /** A video file on disk - read through the Media device plugin's FileName parameter. */
    VIDEO_FILE,

    /** A network camera - read through the Media device plugin's Url parameter (RTSP). */
    IP_CAMERA
}
