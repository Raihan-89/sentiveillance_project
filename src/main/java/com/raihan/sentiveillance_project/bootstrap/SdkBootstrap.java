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
        return sdkRoot;
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
