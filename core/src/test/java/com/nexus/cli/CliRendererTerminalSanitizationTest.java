package com.nexus.cli;

import com.nexus.context.ContextBundle;
import com.nexus.context.ContextItem;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.search.CandidateType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliRendererTerminalSanitizationTest {

    @Test
    void allCliRepresentationsProtectInternalRootsDiagnosticsAndErrors() throws Exception {
        var project = new ProjectDescriptor(UUID.randomUUID(), "project", root,
                ProjectSourceType.LOCAL, Set.of(), Set.of(), null, IndexStatus.READY);
        for (boolean json : List.of(false, true)) {
            var bytes = new ByteArrayOutputStream();
            try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
                var renderer = new CliRenderer(output, output, json);
                renderer.renderProject(project);
                renderer.renderError("Absent " + root.resolve("cache") + " password=12345678", 1);
                var bundle = new ContextBundle(List.of(), 100, 0, List.of(),
                        Map.of("nested", List.of(Map.of("diagnostics", root.resolve("file").toString()))));
                renderer.renderContext(project, "needle", true, 0, bundle);
            }
            String payload = bytes.toString(StandardCharsets.UTF_8);
            assertFalse(payload.contains(root.toString().replace("\\", "\\\\")), payload);
            assertFalse(payload.contains(root.toString()), payload);
            assertFalse(payload.contains(root.toString().replace('\\', '/')), payload);
            assertFalse(payload.contains("12345678"), payload);
        }
    }

    @TempDir
    Path root;

    @Test
    void neutralizesTerminalControlsInHumanContextOutputWithoutChangingJsonContracts() throws Exception {
        String escape = Character.toString(0x1B);
        String bell = Character.toString(0x07);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            CliRenderer renderer = new CliRenderer(output, output, false);
            ProjectDescriptor project = new ProjectDescriptor(
                    UUID.randomUUID(), "project", root, ProjectSourceType.LOCAL,
                    Set.of("java"), Set.of(), null, IndexStatus.READY);
            ContextItem item = new ContextItem(
                    CandidateType.FILE,
                    Path.of("src/Main.java"),
                    null,
                    1,
                    1,
                    "safe" + escape + "]0;owned" + bell + "text",
                    1.0d,
                    Map.of("test", 1.0d),
                    List.of("reason" + escape + "[2J"),
                    10,
                    false);
            ContextBundle bundle = new ContextBundle(
                    List.of(item), 100, 10, List.of("excluded" + escape + "[H"), Map.of());

            renderer.renderContext(project, "query" + escape + "[2J", true, 1L, bundle);
        }

        String rendered = bytes.toString(StandardCharsets.UTF_8);
        assertFalse(rendered.contains(escape));
        assertFalse(rendered.contains(bell));
        assertTrue(rendered.contains("\\u001B"));
        assertTrue(rendered.contains("\\u0007"));
    }
}
