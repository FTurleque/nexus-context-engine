package com.nexus.cli;

import com.nexus.index.CodeSymbol;
import com.nexus.index.SymbolKind;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.CandidateType;
import com.nexus.search.FederatedSearchHit;
import com.nexus.search.SearchCandidate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

/** Les signatures de symboles fournies par SCIP, MINOS ou JDT sortent redigées en JSON et en rendu humain. */
class CliRendererSymbolRedactionTest {

    private static final String GITHUB_TOKEN = "ghp_" + "b".repeat(36);
    private static final String PASSWORD = "hunter2-signature-secret";

    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {"scip", "minos", "jdt"})
    void externalSignaturesAreRedactedOnEveryCliOutput(String provider) throws Exception {
        ProjectDescriptor project = new ProjectDescriptor(UUID.randomUUID(), "demo", root, ProjectSourceType.LOCAL,
                Set.of(), Set.of(), null, IndexStatus.READY);
        CodeSymbol symbol = new CodeSymbol(SymbolKind.TYPE, "TOKEN", "demo.Config.TOKEN",
                "String TOKEN = \"" + GITHUB_TOKEN + "\"; password = \"" + PASSWORD + "\"", 2, 2, provider);
        RankedCandidate ranked = new RankedCandidate(new SearchCandidate("symbol:src/Config.java:demo.Config.TOKEN",
                CandidateType.SYMBOL, root.resolve("src/Config.java"), symbol, "TOKEN", Map.of()),
                1.0d, Map.of("symbol", 1.0d), List.of());

        for (boolean json : new boolean[]{true, false}) {
            String output = render(json, renderer -> {
                renderer.renderSearch(project, "TOKEN", 5, true, 1, List.of(ranked));
                renderer.renderFederatedSearch(List.of(project), "TOKEN", 5, true, 1,
                        List.of(new FederatedSearchHit(project, ranked)));
            });
            assertFalse(output.contains(GITHUB_TOKEN), output);
            assertFalse(output.contains(PASSWORD), output);
            assertTrue(output.contains("[REDACTED]"), output);
        }
    }

    private static String render(boolean json, Rendering rendering) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) {
            rendering.render(new CliRenderer(out, err, json));
        }
        return stdout.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface Rendering {
        void render(CliRenderer renderer) throws Exception;
    }
}
