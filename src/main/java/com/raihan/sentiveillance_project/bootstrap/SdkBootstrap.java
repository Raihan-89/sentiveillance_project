package com.raihan.sentiveillance_project.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Puts the SentiVeillance native libraries on the JNA search path.
 *
 * <p>Every {@code com.neurotec.*} class is a thin JNA binding: its static initialiser
 * resolves {@code libNCore.so} / {@code NCore.dll} through {@code jna.library.path}. That
 * means this has to run <em>before the first Neurotec class is loaded</em>, which is why
 * {@code main()} calls it before {@code SpringApplication.run(...)} rather than doing it
 * from a Spring bean.
 *
 * <p>The logic mirrors the SDK's own
 * {@code Tutorials/Common/Java/utils/.../LibraryManager.java}, except that the SDK root is
 * configured explicitly instead of being derived from the working directory (the SDK
 * samples must be launched from {@code Bin/Java} for their version to work).
 *
 * <p>The model files in {@code Bin/Data} (SurveillanceFaces.ndf, FacesDetect*.ndf, ...) are
 * found by the native code itself, which looks for {@code ../../Bin/Data} relative to the
 * shared library. So the SDK directory tree must be kept intact - do not copy the .so files
 * out on their own.
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

    /**
     * Resolves the SDK root, verifies the native directory exists and prepends it to
     * {@code jna.library.path}.
     *
     * @return the resolved SDK root
     */
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
     *
     * <p>Two groups, failing for two different reasons:
     * <ul>
     *   <li><b>OpenCV</b> - needed by {@code libNSurveillance.so}. All nine were built with an
     *       unusable {@code RUNPATH}: eight carry {@code /tmp/working/build/lib} and
     *       {@code libopencv_core} carries a row of empty entries.</li>
     *   <li><b>ffmpeg / VLC / OpenSSL 1.1</b> - {@code dlopen}ed at runtime by
     *       {@code libNMedia.so} rather than linked, and they carry <em>no</em> {@code RUNPATH}
     *       at all. Without these the source connects and then stops immediately with no error
     *       in the log, and zero events are produced.</li>
     * </ul>
     * The list was derived from the SDK's own {@code DT_NEEDED} entries; see
     * {@link #preloadBrokenRunpathLibraries} for why loading them early is what fixes it.
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
     *
     * <h2>Why this is needed</h2>
     * All nine {@code libopencv_*.so.4.4} files in the SDK were built with a useless
     * {@code RUNPATH}: eight carry {@code /tmp/working/build/lib} (Neurotechnology's build
     * machine) and {@code libopencv_core} carries a row of empty entries. The dynamic loader
     * resolves a library's {@code DT_NEEDED} entries using <em>that library's own</em>
     * runpath, so the {@code $ORIGIN} runpath on {@code libNSurveillance.so} covers its direct
     * dependencies but not what those pull in underneath. Loading
     * {@code libNSurveillance.so} therefore fails with
     * {@code UnsatisfiedLinkError: libopencv_features2d.so.4.4: cannot open shared object file}
     * unless {@code LD_LIBRARY_PATH} happens to include the SDK directory - and that variable
     * is read by the loader when the process starts, so it cannot be set from inside the JVM.
     *
     * <h2>Why loading them early fixes it</h2>
     * {@code System.load} takes an absolute path, so the broken runpaths never come into play.
     * Once a library is in the process, the loader satisfies later {@code DT_NEEDED}
     * references by SONAME from what is already loaded instead of searching the filesystem
     * again. Every file here has a SONAME equal to its file name, which is exactly the string
     * the dependent libraries ask for, so by the time JNA loads
     * {@code libNSurveillance.so} every OpenCV dependency is already satisfied.
     *
     * <p>Best effort by design: when {@code LD_LIBRARY_PATH} is set (as {@code scripts/run.sh}
     * does) these loads are simply redundant, and a future SDK that fixes its runpaths or
     * renames these files must not stop the application from starting.
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

    /**
     * Warns when {@code LD_LIBRARY_PATH} does not cover the native directory.
     *
     * <p>Setting {@code jna.library.path} above is enough for JNA to <em>find</em>
     * {@code libNSurveillance.so}, but not for the OS loader to resolve that library's own
     * dependency tree. {@code libopencv_calib3d.so.4.4} ships with
     * {@code RUNPATH=/tmp/working/build/lib} - Neurotechnology's build machine - so the
     * {@code $ORIGIN} runpath on {@code libNSurveillance.so} does not extend to
     * {@code libopencv_features2d}/{@code libopencv_flann} underneath it. The only remaining
     * mechanism is {@code LD_LIBRARY_PATH}, which the loader reads when the process starts:
     * by the time this method runs the JVM is already up, so it cannot be fixed from here.
     *
     * <p>This only warns, never throws. A machine with the directory configured through
     * {@code ldconfig} loads fine, and failing fast there would be wrong. Without the warning
     * the symptom is a 300-line {@code UnsatisfiedLinkError} whose real cause
     * ("cannot open shared object file: libopencv_features2d.so.4.4") is buried in the middle.
     */
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

    /**
     * Where the platform's Neurotec shared libraries live inside the SDK tree.
     * Linux keeps them under {@code Lib/<platform>}; Windows keeps them under
     * {@code Bin/Win64_x64}. Same split as the SDK's LibraryManager.
     */
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
