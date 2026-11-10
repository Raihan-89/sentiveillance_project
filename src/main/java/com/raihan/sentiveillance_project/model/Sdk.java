package com.raihan.sentiveillance_project.model;

import lombok.Getter;
import lombok.Setter;

/**
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 10 November, 2026
 */
@Getter
@Setter
public class Sdk {
    /** Absolute path to SentiVeillance_10_1_SDK. Resolved by SdkBootstrap at startup. */
    private String root;
    /**
     * Trial mode. The SDK ships Bin/Licenses/TrialFlag.txt = TRUE, and pgd.conf has
     * trial = true, so this must stay true until you activate a purchased license.
     */
    private boolean trialMode = true;
    /** Licensing service address passed to NLicense.obtain. "/local" = the local pgd daemon. */
    private String licenseServer = "/local";
    /** Port passed to NLicense.obtain. 5000 is the pgd default. */
    private int licensePort = 5000;
    /**
     * Force the licensing client to use HTTPS when talking to Neurotechnology's
     * servers. Null leaves the SDK default alone. Mirrors the "https" key in pgd.conf.
     */
    private Boolean useHttps;
    /**
     * Where the licensing client caches obtained licences (a trial licence is
     * downloaded and cached here). Null leaves the SDK default alone - override only
     * if the default location turns out not to be writable.
     */
    private String licenseStoragePath;

    /**
     * Run detection on an NVIDIA GPU. Needs a CUDA-capable card plus the TensorRT
     * libraries that ship in Lib/Linux_x86_64. Leave false to run on CPU.
     */
    private boolean useGpu = false;
}
