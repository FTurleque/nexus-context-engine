package com.nexus.context;

import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.search.CandidateType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Un fragment portant une valeur sensible trop longue est écarté seul ; le contexte se construit. */
class OversizedSecretContextTest {

    private static final String VALUE = "z".repeat(5_000);

    @TempDir
    Path temporary;

    @Test
    void contextIsBuiltWithoutTheFragmentWhoseSecretExceedsTheBound() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("project"));
        Files.writeString(root.resolve("visible.md"), "# sharedMarker visible\n");
        Files.writeString(root.resolve("leak.md"), "# sharedMarker leak\n");
        try (var application = NexusApplication.create(new NexusPaths(temporary.resolve("home")))) {
            var project = application.registerProject(root, "context-oversized");
            application.index(project.id(), true, false);
            // Le fichier devient non redigeable après l'indexation : la construction de contexte le relit.
            Files.writeString(root.resolve("leak.md"), "# sharedMarker leak\npassword = \"" + VALUE + "\"\n");

            var operation = application.context(project.id(), "sharedMarker", 2_000, Set.of(), Map.of(), true);

            String rendered = operation.bundle().toString();
            assertFalse(rendered.contains("zzzz"));
            assertTrue(rendered.contains("valeur sensible dépassant la limite de redaction"), rendered);
            assertTrue(operation.bundle().items().stream()
                    .anyMatch(item -> item.path().toString().endsWith("visible.md")), rendered);
            assertTrue(operation.bundle().items().stream()
                    .noneMatch(item -> item.path().toString().endsWith("leak.md")), rendered);
        }
    }

    @Test
    void bundleDropsAnItemWhoseSecretExceedsTheBoundAndRecordsTheExclusion() {
        ContextItem visible = new ContextItem(CandidateType.FILE, Path.of("visible.md"), null, 1, 1,
                "visible", 1.0d, Map.of(), List.of(), 1, false);
        ContextItem leak = new ContextItem(CandidateType.FILE, Path.of("leak.md"), null, 1, 1,
                "password = \"" + VALUE + "\"", 1.0d, Map.of(), List.of(), 1, false);

        ContextBundle bundle = new ContextBundle(List.of(visible, leak), 10, 2, List.of(), Map.of());

        assertEquals(List.of(visible.path()), bundle.items().stream().map(ContextItem::path).toList());
        assertEquals(List.of("leak.md exclu : valeur sensible dépassant la limite de redaction"), bundle.excluded());
    }

    @Test
    void selectorExcludesOnlyTheOversizedFragment() {
        var selector = new BudgetedContextSelector(new com.nexus.token.HeuristicTokenEstimator());
        ContextFragment visible = new ContextFragment(CandidateType.FILE, Path.of("visible.md"), null, 1, 1,
                "visible", 1.0d, Map.of(), List.of());
        ContextFragment leak = new ContextFragment(CandidateType.FILE, Path.of("leak.md"), null, 1, 1,
                "password = \"" + VALUE + "\"", 2.0d, Map.of(), List.of());

        ContextSelectionResult result = selector.select(List.of(leak, visible), 100, true);

        assertEquals(List.of(visible.path()), result.items().stream().map(ContextItem::path).toList());
        assertTrue(result.excluded().contains("leak.md exclu : valeur sensible dépassant la limite de redaction"));
    }
}
