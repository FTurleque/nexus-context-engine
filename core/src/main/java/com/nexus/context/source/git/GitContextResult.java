package com.nexus.context.source.git;

import com.nexus.context.ContextFragment;

import java.util.List;
import java.util.Objects;

public record GitContextResult(
        List<ContextFragment> fragments,
        boolean enabled,
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

    public static GitContextResult disabled(String diagnostic) {
        return new GitContextResult(List.of(), false, false, 0, 0, 0, List.of(diagnostic));
    }

    public static GitContextResult unavailable(String diagnostic) {
        return new GitContextResult(List.of(), true, false, 0, 0, 0, List.of(diagnostic));
    }
}
