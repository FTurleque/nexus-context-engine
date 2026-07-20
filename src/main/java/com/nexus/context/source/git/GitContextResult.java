package com.nexus.context.source.git;

import com.nexus.context.ContextFragment;

import java.util.List;
import java.util.Objects;

public record GitContextResult(
        List<ContextFragment> fragments,
        boolean repositoryAvailable,
        int commitsInspected,
        int relatedCommits,
        int coChangeLinks,
        List<String> diagnostics) {

    public GitContextResult {
        Objects.requireNonNull(fragments, "fragments");
        Objects.requireNonNull(diagnostics, "diagnostics");
        fragments = List.copyOf(fragments);
        diagnostics = List.copyOf(diagnostics);
    }

    public static GitContextResult unavailable(String diagnostic) {
        return new GitContextResult(List.of(), false, 0, 0, 0, List.of(diagnostic));
    }
}
