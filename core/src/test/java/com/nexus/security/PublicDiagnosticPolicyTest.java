package com.nexus.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PublicDiagnosticPolicyTest {
    @TempDir Path root;

    @Test
    void projectsBothSeparatorsAndRedactsForeignPathsAndSecretsRecursively() {
        var policy = new PublicDiagnosticPolicy(List.of(root));
        String file = root.resolve("src/App.java").toString();
        for (String path : List.of(file, file.replace('\\', '/'))) {
            String safe = policy.text("Absent : " + path + " (password=12345678)");
            assertFalse(safe.contains(root.toString()));
            assertTrue(safe.contains("src"));
            assertFalse(safe.contains("12345678"));
        }
        var result = policy.metadata(Map.of("nested", List.of(Map.of("diagnostic",
                "/home/alice/project/file C:\\Users\\alice\\secret \\\\internal\\share\\private /tmp/cache"))));
        String safe = result.toString();
        for (String forbidden : List.of("/home/", "C:\\Users", "\\\\internal", "/tmp/")) {
            assertFalse(safe.contains(forbidden), safe);
        }
        assertEquals("src/App.java", policy.text("src/App.java"));
        assertEquals("./src/App.java", policy.text("./src/App.java"));
        assertEquals("[INTERNAL_PATH]", policy.text("file:///tmp/internal/cache"));
        assertEquals("[INTERNAL_PATH]", policy.text("file:///C:/Users/internal/cache"));
    }

    @Test
    void rejectsUnknownObjectsCyclesOversizedTextAndNonFiniteNumbers() {
        var policy = PublicDiagnosticPolicy.internal();
        assertThrows(IllegalStateException.class, () -> policy.metadata(Map.of("x", new Object())));
        assertThrows(IllegalStateException.class, () -> policy.metadata(Map.of("x", Double.NaN)));
        assertThrows(IllegalStateException.class, () -> policy.text("x".repeat(1_048_577)));
        Map<String, Object> cycle = new java.util.HashMap<>();
        cycle.put("cycle", cycle);
        assertThrows(IllegalStateException.class, () -> policy.metadata(cycle));
    }
}
