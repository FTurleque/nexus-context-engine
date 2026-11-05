package com.nexus.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.context.ContextBundle;
import com.nexus.context.ContextItem;
import com.nexus.context.FederatedContextBundle;
import com.nexus.context.FederatedContextItem;
import com.nexus.exposure.PublicOutputProjection;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.CandidateType;
import com.nexus.search.FederatedSearchHit;
import com.nexus.search.SearchCandidate;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * La sérialisation REST de {@code scoreComponents}, par l'{@link ObjectMapper} configuré par Quarkus,
 * suit l'ordre des clés quel que soit l'ordre d'insertion.
 */
@QuarkusTest
class RestScoreComponentsOrderTest {

    private static final String SORTED = "\"scoreComponents\":{\"graph\":0.1,\"lexical\":0.2,\"path\":0.3,\"semantic\":0.4,\"symbol\":0.5}";

    @Inject
    ObjectMapper objectMapper;

    @TempDir
    Path temporary;

    @Test
    void searchAndContextSerializeScoreComponentsInKeyOrder() throws Exception {
        ProjectDescriptor project = new ProjectDescriptor(UUID.randomUUID(), "order", temporary,
                ProjectSourceType.LOCAL, Set.of(), Set.of(), null, IndexStatus.READY);

        String first = serialize(project, List.of("graph", "lexical", "path", "semantic", "symbol"));
        String second = serialize(project, List.of("symbol", "semantic", "path", "lexical", "graph"));

        assertEquals(first, second);
        assertEquals(4, first.split(java.util.regex.Pattern.quote(SORTED), -1).length - 1, first);
    }

    private String serialize(ProjectDescriptor project, List<String> order) throws Exception {
        Map<String, Double> components = new LinkedHashMap<>();
        for (String key : order) {
            components.put(key, switch (key) {
                case "graph" -> 0.1d;
                case "lexical" -> 0.2d;
                case "path" -> 0.3d;
                case "semantic" -> 0.4d;
                default -> 0.5d;
            });
        }
        RankedCandidate ranked = new RankedCandidate(new SearchCandidate("file:App.java", CandidateType.FILE,
                project.rootPath().resolve("App.java"), null, "class App {}", Map.of()), 1.0d, components, List.of());
        ContextItem item = new ContextItem(CandidateType.FILE, project.rootPath().resolve("App.java"), null,
                1, 1, "class App {}", 1.0d, components, List.of(), 5, false);
        return objectMapper.writeValueAsString(List.of(
                PublicOutputProjection.search(project, "q", 1, false, 0, List.of(ranked)),
                PublicOutputProjection.federatedSearch(List.of(project), "q", 1, false, 0,
                        List.of(new FederatedSearchHit(project, ranked))),
                PublicOutputProjection.context(project, "q", false, 0,
                        new ContextBundle(List.of(item), 10, 5, List.of(), Map.of())),
                PublicOutputProjection.federatedContext(List.of(project), "q", false, 0,
                        new FederatedContextBundle(List.of(new FederatedContextItem(project, item)), 10, 5,
                                List.of(), Map.of()))));
    }
}
