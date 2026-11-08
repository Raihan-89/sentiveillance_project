package com.raihan.sentiveillance_project;

import com.raihan.sentiveillance_project.bootstrap.SdkBootstrap;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure path logic - no SDK classes are touched, so this runs anywhere.
 *
 * @author Md. Raihan Shikder (Raihan-89)
 * @date 8 November, 2026
 */
class SdkBootstrapTest {

    @Test
    void nativeDirectoryFollowsPlatformLayout() {
        Path root = Path.of("/opt/SentiVeillance_10_1_SDK");
        Path nativeDir = SdkBootstrap.nativeDirectory(root);
        String s = nativeDir.toString();
        assertTrue(s.startsWith(root.toString()), "native dir must live inside the SDK tree: " + s);
        assertTrue(s.contains("Linux_x86_64") || s.contains("Linux_arm64") || s.contains("Win64_x64"),
                "unexpected native dir: " + s);
    }
}
