package com.nexus.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.context.FederatedContextBundle;
import com.nexus.exposure.PublicOutput;
import com.nexus.exposure.PublicOutputProjection;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BE-0081 : le diagnostic des projets non servis sort de la projection publique unique, dans
 * l'ordre du scope ; la CLI le sérialise tel quel (REST et MCP utilisent la même projection et
 * leurs tests de parité vérifient la clé {@code unservedProjects} du contrat).
 */
class FederatedUnservedProjectsDiagnosticTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    @Test
    void projectionNamesUnservedProjectsInScopeOrderForSearchAndContext() {
        List<ProjectDescriptor> scope = scope();
        List<ProjectDescriptor> unserved = scope.subList(2, 5);
        List<String> expected = unserved.stream().map(project -> project.id().toString()).toList();

        PublicOutput.FederatedSearch search = PublicOutputProjection.federatedSearch(
                scope, "q", 5, false, 1, List.of(), unserved);
        PublicOutput.FederatedContext context = PublicOutputProjection.federatedContext(
                scope, "q", false, 1,
                new FederatedContextBundle(List.of(), 100, 0, List.of(), Map.of(), unserved));

        assertEquals(expected, search.unservedProjects().stream().map(PublicOutput.Project::id).toList());
        assertEquals(expected, context.unservedProjects().stream().map(PublicOutput.Project::id).toList());
        assertEquals(List.of("c", "d", "e"),
                search.unservedProjects().stream().map(PublicOutput.Project::name).toList());
    }

    @Test
    void cliJsonCarriesTheSameDiagnosticAsTheProjection() throws Exception {
        List<ProjectDescriptor> scope = scope();
        List<ProjectDescriptor> unserved = scope.subList(3, 5);

        String output = render(true, renderer -> renderer.renderFederatedSearch(
                scope, "q", 5, false, 1, List.of(), unserved));

        JsonNode payload = JSON.readTree(output);
        assertEquals(
                JSON.valueToTree(PublicOutputProjection.projects(unserved)),
                payload.get("unservedProjects"));
    }

    @Test
    void cliTextNamesUnservedProjectsOnlyWhenThereAreSome() throws Exception {
        List<ProjectDescriptor> scope = scope();

        String interrupted = render(false, renderer -> renderer.renderFederatedSearch(
                scope, "q", 5, false, 1, List.of(), scope.subList(3, 5)));
        String complete = render(false, renderer -> renderer.renderFederatedSearch(
                scope, "q", 5, false, 1, List.of(), List.of()));

        assertTrue(interrupted.contains("Projets non servis"), interrupted);
        assertTrue(interrupted.contains("d, e"), interrupted);
        assertTrue(!complete.contains("Projets non servis"), complete);
    }

    private List<ProjectDescriptor> scope() {
        return List.of("a", "b", "c", "d", "e").stream()
                .map(name -> new ProjectDescriptor(
                        UUID.randomUUID(), name, root.resolve(name), ProjectSourceType.LOCAL,
                        Set.of(), Set.of(), null, IndexStatus.READY))
                .toList();
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
