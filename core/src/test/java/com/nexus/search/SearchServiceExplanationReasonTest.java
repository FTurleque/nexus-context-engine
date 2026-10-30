package com.nexus.search;

import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.ranking.DeterministicContextRanker;
import com.nexus.ranking.RankedCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchServiceExplanationReasonTest {

    private static final String REASON = "signal indisponible";

    @TempDir
    Path temporaryDirectory;

    @Test
    void appendsEnricherResultReasonsOnceToEveryExplainedCandidate() throws Exception {
        List<RankedCandidate> ranked = service().search(project(), "needle", 10, true);

        assertEquals(2, ranked.size());
        for (RankedCandidate candidate : ranked) {
            assertEquals(REASON, candidate.reasons().getLast());
            assertEquals(1, candidate.reasons().stream().filter(REASON::equals).count());
        }
    }

    @Test
    void leavesReasonsEmptyWithoutExplanation() throws Exception {
        List<RankedCandidate> ranked = service().search(project(), "needle", 10, false);

        assertEquals(2, ranked.size());
        ranked.forEach(candidate -> assertEquals(List.of(), candidate.reasons()));
    }

    @Test
    void twoArgumentEnricherIsCalledThroughTheDefaultMethod() throws Exception {
        CandidateEnricher plain = (project, candidates) -> List.of();
        List<String> reasons = new ArrayList<>();

        assertEquals(List.of(), plain.enrich(project(), List.of(candidate("a")), reasons::add));
        assertEquals(List.of(), reasons);
    }

    private SearchService service() {
        SearchStrategy strategy = (project, query, limit) -> List.of(candidate("a"), candidate("b"));
        CandidateEnricher failing = new CandidateEnricher() {
            @Override
            public List<SearchCandidate> enrich(ProjectDescriptor project, List<SearchCandidate> candidates) {
                return candidates;
            }

            @Override
            public List<SearchCandidate> enrich(ProjectDescriptor project, List<SearchCandidate> candidates,
                                                Consumer<String> explanationReasons) {
                explanationReasons.accept(REASON);
                explanationReasons.accept(REASON);
                return candidates;
            }
        };
        return new SearchService(List.of(strategy), List.of(failing), new DeterministicContextRanker());
    }

    private SearchCandidate candidate(String id) {
        return new SearchCandidate(id, CandidateType.FILE, temporaryDirectory.resolve(id + ".java"), null, id,
                Map.of(SearchSignals.LEXICAL, 0.5d));
    }

    private ProjectDescriptor project() {
        return new ProjectDescriptor(UUID.randomUUID(), "explain", temporaryDirectory.toAbsolutePath().normalize(),
                ProjectSourceType.LOCAL, Set.of(), Set.of(), null, IndexStatus.READY);
    }
}
