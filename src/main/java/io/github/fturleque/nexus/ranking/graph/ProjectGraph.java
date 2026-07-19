package io.github.fturleque.nexus.ranking.graph;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public record ProjectGraph(Map<String, Set<String>> adjacency) {

    public ProjectGraph {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        adjacency.forEach((path, neighbors) -> copy.put(path, Set.copyOf(neighbors)));
        adjacency = Map.copyOf(copy);
    }

    public Set<String> neighbors(String relativePath) {
        return adjacency.getOrDefault(relativePath, Set.of());
    }

    public static ProjectGraph undirected(Map<String, Set<String>> directedEdges) {
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        directedEdges.forEach((source, targets) -> {
            adjacency.computeIfAbsent(source, ignored -> new LinkedHashSet<>());
            for (String target : targets) {
                adjacency.computeIfAbsent(source, ignored -> new LinkedHashSet<>()).add(target);
                adjacency.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(source);
            }
        });
        return new ProjectGraph(adjacency);
    }
}
