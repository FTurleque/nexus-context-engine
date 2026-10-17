package com.nexus.context;

import com.nexus.context.source.ContextSourceDescriptor;
import com.nexus.context.source.ContextSourceDiscoveryResult;
import com.nexus.project.ProjectDescriptor;
import com.nexus.ranking.RankedCandidate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.nexus.context.ContextPipelineState.TaskFragments;

/** Étape interne et déterministe de construction du contexte. */
final class TaskContextMaterializer {
    private final ContextFragmentFactory fragmentFactory;
    private final FragmentMerger fragmentMerger;
    TaskContextMaterializer(ContextFragmentFactory factory, FragmentMerger merger) {
        fragmentFactory = factory; fragmentMerger = merger;
    }
    TaskFragments materialize(ContextRequest request, ProjectDescriptor project, List<RankedCandidate> filtered,
            ContextSourceDiscoveryResult nativeDiscovery, ContextMaterializationBudget materializationBudget) throws IOException {
        ContextFragmentFactory.MaterializationResult taskMaterialization = fragmentFactory.materialize(
                project,
                request.query(),
                filtered,
                request.tokenBudget(),
                materializationBudget);
        List<ContextFragment> taskFragments = taskMaterialization.fragments();
        Set<Path> nativePaths = nativeDiscovery.sources().stream()
                .map(ContextSourceDescriptor::path)
                .map(Path::normalize)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<ContextFragment> deduplicatedTaskFragments = taskFragments.stream()
                .filter(fragment -> !nativePaths.contains(fragment.path().normalize()))
                .toList();
        List<ContextFragment> mergedTaskFragments = fragmentMerger.merge(deduplicatedTaskFragments);
        return new TaskFragments(taskMaterialization, deduplicatedTaskFragments, mergedTaskFragments);
    }
}
