package com.nexus.index.jdt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EquinoxLauncherSelectorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void comparesNumericVersionComponentsInsteadOfLauncherFileNamesLexicographically() throws Exception {
        Path plugins = Files.createDirectory(temporaryDirectory.resolve("plugins"));
        Files.createFile(plugins.resolve("org.eclipse.equinox.launcher_1.9.0.v20260101-0000.jar"));
        Path expected = Files.createFile(
                plugins.resolve("org.eclipse.equinox.launcher_1.10.0.v20260101-0000.jar"));

        try (var files = Files.list(plugins)) {
            assertEquals(expected, EquinoxLauncherSelector.latest(files).orElseThrow());
        }
    }
}
