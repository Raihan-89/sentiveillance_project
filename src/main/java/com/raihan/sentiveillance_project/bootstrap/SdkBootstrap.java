package com.raihan.sentiveillance_project.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Puts the SentiVeillance native libraries on the JNA search path.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 8 November, 2026
 */
public final class SdkBootstrap {

    public static final String SDK_ROOT_PROPERTY = "sentiveillance.sdk.root";
    public static final String SDK_ROOT_ENV = "SENTIVEILLANCE_SDK_ROOT";

    private static final String DEFAULT_RELATIVE_ROOT = "my_files/sdk/SentiVeillance_10_1_SDK";

    private SdkBootstrap() {
    }

    public static Path initLibraryPath() {
        Path sdkRoot = resolveSdkRoot();
        Path nativeDir = nativeDirectory(sdkRoot);

        if (!Files.isDirectory(nativeDir)) {
            throw new IllegalStateException(String.format(
                    "SentiVeillance native libraries not found at %s%n"
                            + "Set -D%s=/path/to/SentiVeillance_10_1_SDK or %s=/path/to/SentiVeillance_10_1_SDK.",
                    nativeDir, SDK_ROOT_PROPERTY, SDK_ROOT_ENV));
        }

        // Re-publish the resolved root so Spring configuration can read it back.
        System.setProperty(SDK_ROOT_PROPERTY, sdkRoot.toString());
        prepend("jna.library.path", nativeDir.toString());
        prepend("java.library.path", nativeDir.toString());

        System.out.printf("[SdkBootstrap] SDK root      : %s%n", sdkRoot);
        System.out.printf("[SdkBootstrap] native libs   : %s%n", nativeDir);
        System.out.printf("[SdkBootstrap] model data    : %s%n", sdkRoot.resolve("Bin/Data"));
        preloadBrokenRunpathLibraries(nativeDir);
        return sdkRoot;
    }

    /**
     * Bundled libraries that have to be loaded by absolute path, in dependency order
     * (dependencies first). Order matters: each is loaded by full path, and whatever it needs
     * from this same list must already be in the process when it is.
     */
    private static final String[] BROKEN_RUNPATH_LIBRARIES = {
            // --- OpenCV, for libNSurveillance.so ---
            "libopencv_core.so.4.4",
            "libopencv_flann.so.4.4",
            "libopencv_imgproc.so.4.4",
            "libopencv_features2d.so.4.4",
            "libopencv_calib3d.so.4.4",
            "libopencv_dnn.so.4.4",
            "libopencv_ml.so.4.4",
            "libopencv_objdetect.so.4.4",
            "libopencv_video.so.4.4",
            // --- media backends, dlopened by libNMedia.so ---
            // libcrypto/libssl are the OpenSSL 1.1 pair from scripts/install-missing-libs.sh;
            // they are absent until that script has been run, which the loop tolerates.
            "libcrypto.so.1.1",
            "libssl.so.1.1",
            "libavutil.so.59",
            "libswresample.so.5",
            "libswscale.so.8",
            "libavcodec.so.61",
            "libavformat.so.61",
            "libavfilter.so.10",
            "libavdevice.so.61",
            "libvlccore.so.9",
            "libvlc.so.5",
    };

    /**
     * Loads the bundled OpenCV libraries by absolute path, so {@code libNSurveillance.so} can
     * be loaded without {@code LD_LIBRARY_PATH} being set.
     */
    private static void preloadBrokenRunpathLibraries(Path nativeDir) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            return;
        }
        int loaded = 0;
        for (String library : BROKEN_RUNPATH_LIBRARIES) {
            Path file = nativeDir.resolve(library);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                System.load(file.toString());
                loaded++;
            } catch (UnsatisfiedLinkError e) {
                System.err.printf("[SdkBootstrap] could not preload %s: %s%n", library, e.getMessage());
            }
        }
        int expected = 0;
        for (String library : BROKEN_RUNPATH_LIBRARIES) {
            if (Files.isRegularFile(nativeDir.resolve(library))) {
                expected++;
            }
        }
        System.out.printf("[SdkBootstrap] native preload: %d/%d present (works around the SDK's "
                + "unusable RUNPATHs)%n", loaded, expected);
        if (loaded < expected) {
            // Preloading is the primary fix; the environment variable is the fallback.
            warnIfLoaderPathMissing(nativeDir);
        }
    }

    /** Warns when {@code LD_LIBRARY_PATH} does not cover the native directory. */
    static void warnIfLoaderPathMissing(Path nativeDir) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            return;
        }
        if (onLoaderPath(System.getenv("LD_LIBRARY_PATH"), nativeDir)) {
            System.out.printf("[SdkBootstrap] LD_LIBRARY_PATH: ok%n");
            return;
        }
        System.err.printf(
                "%n[SdkBootstrap] ***  LD_LIBRARY_PATH does not contain  ***%n"
                        + "[SdkBootstrap]   %s%n"
                        + "[SdkBootstrap] Loading libNSurveillance.so will fail with%n"
                        + "[SdkBootstrap]   UnsatisfiedLinkError: libopencv_features2d.so.4.4: cannot open shared object file%n"
                        + "[SdkBootstrap] because libopencv_calib3d.so.4.4 was built with RUNPATH=/tmp/working/build/lib.%n"
                        + "[SdkBootstrap] It must be set before the JVM starts - this cannot be fixed from Java.%n"
                        + "[SdkBootstrap]%n"
                        + "[SdkBootstrap]   shell    : use scripts/run.sh (it exports it), or export it yourself:%n"
                        + "[SdkBootstrap]              export LD_LIBRARY_PATH=%s%n"
                        + "[SdkBootstrap]   IntelliJ : Run > Edit Configurations > Environment variables:%n"
                        + "[SdkBootstrap]              LD_LIBRARY_PATH=%s%n"
                        + "[SdkBootstrap]              (or pick the shared 'Sentiveillance (with SDK)' configuration)%n%n",
                nativeDir, nativeDir, nativeDir);
    }

    /** True when one of the {@code LD_LIBRARY_PATH} entries is the native directory. */
    private static boolean onLoaderPath(String loaderPath, Path nativeDir) {
        if (isBlank(loaderPath)) {
            return false;
        }
        for (String entry : loaderPath.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                if (Paths.get(entry).toAbsolutePath().normalize().equals(nativeDir)) {
                    return true;
                }
            } catch (RuntimeException e) {
                // A malformed entry is simply not a match.
            }
        }
        return false;
    }

    public static Path resolveSdkRoot() {
        String configured = System.getProperty(SDK_ROOT_PROPERTY);
        if (isBlank(configured)) {
            configured = System.getenv(SDK_ROOT_ENV);
        }
        Path root = isBlank(configured) ? Paths.get(DEFAULT_RELATIVE_ROOT) : Paths.get(configured);
        return root.toAbsolutePath().normalize();
    }

    /** Where the platform's Neurotec shared libraries live inside the SDK tree. */
    public static Path nativeDirectory(Path sdkRoot) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        if (os.contains("win")) {
            return sdkRoot.resolve("Bin").resolve("Win64_x64");
        }
        if (os.contains("linux")) {
            boolean arm = arch.contains("aarch64") || arch.contains("arm");
            return sdkRoot.resolve("Lib").resolve(arm ? "Linux_arm64" : "Linux_x86_64");
        }
        throw new IllegalStateException("Unsupported platform for SentiVeillance SDK: " + os + " / " + arch);
    }

    private static void prepend(String property, String value) {
        String current = System.getProperty(property);
        if (isBlank(current)) {
            System.setProperty(property, value);
        } else if (!current.contains(value)) {
            System.setProperty(property, value + java.io.File.pathSeparator + current);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
