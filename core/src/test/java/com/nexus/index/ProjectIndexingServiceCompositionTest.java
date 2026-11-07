package com.nexus.index;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Composition de {@link ProjectIndexingService} : un constructeur public, un verrou explicite. */
class ProjectIndexingServiceCompositionTest {

    /** Le répertoire de travail de surefire est la racine du module ou celle du reactor. */
    private static final Path MAIN_SOURCES = Files.isDirectory(Path.of("src", "main", "java"))
            ? Path.of("src", "main", "java")
            : Path.of("core", "src", "main", "java");

    @Test
    void exposesASinglePublicConstructorThatRequiresALockManager() {
        List<Constructor<?>> publicConstructors = Arrays.stream(ProjectIndexingService.class.getConstructors())
                .filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
                .toList();

        assertEquals(1, publicConstructors.size());
        assertTrue(Arrays.asList(publicConstructors.getFirst().getParameterTypes())
                .contains(ProjectIndexLockManager.class));
    }

    @Test
    void noProductionCodeFallsBackToProcessLocalOnlyLocking() throws IOException {
        assertTrue(Files.isDirectory(MAIN_SOURCES), "Sources de production introuvables : " + MAIN_SOURCES.toAbsolutePath());
        List<Path> offenders;
        try (Stream<Path> sources = Files.walk(MAIN_SOURCES)) {
            offenders = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("ProjectIndexLockManager.java"))
                    .filter(ProjectIndexingServiceCompositionTest::callsProcessLocalOnly)
                    .toList();
        }

        assertEquals(List.of(), offenders);
    }

    private static boolean callsProcessLocalOnly(Path source) {
        try {
            return Files.readString(source).contains("processLocalOnly(");
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }
}
