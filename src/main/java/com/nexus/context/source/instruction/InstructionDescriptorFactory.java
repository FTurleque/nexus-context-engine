package com.nexus.context.source.instruction;

import com.nexus.context.source.ContextSourceDescriptor;
import com.nexus.context.source.ContextSourceScope;
import com.nexus.project.ProjectDescriptor;
import com.nexus.search.CandidateType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class InstructionDescriptorFactory {

    private final InstructionReferenceResolver referenceResolver = new InstructionReferenceResolver();

    List<ContextSourceDescriptor> create(
            ProjectDescriptor project,
            String provider,
            String origin,
            Path absolutePath,
            ContextSourceScope scope,
            List<String> applyTo,
            int priority,
            List<String> reasons,
            boolean resolveReferences) throws IOException {
        Path relativePath = InstructionDiscoverySupport.relative(project, absolutePath);
        List<ContextSourceDescriptor> descriptors = new ArrayList<>();
        descriptors.add(new ContextSourceDescriptor(
                provider + ":" + InstructionDiscoverySupport.repositoryPath(relativePath),
                CandidateType.INSTRUCTION,
                provider,
                origin,
                relativePath,
                scope,
                applyTo,
                priority,
                InstructionDiscoverySupport.read(absolutePath),
                Map.of(),
                reasons));

        if (!resolveReferences) {
            return List.copyOf(descriptors);
        }

        for (InstructionReferenceResolver.ResolvedReference reference : referenceResolver.resolve(project, absolutePath)) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("referencedFrom", InstructionDiscoverySupport.repositoryPath(relativePath));
            metadata.put("referenceDepth", reference.depth());
            descriptors.add(new ContextSourceDescriptor(
                    provider + ":reference:" + InstructionDiscoverySupport.repositoryPath(reference.relativePath()),
                    CandidateType.INSTRUCTION,
                    provider,
                    origin + "_REFERENCE",
                    reference.relativePath(),
                    scope,
                    applyTo,
                    Math.max(0, priority - 5 - reference.depth()),
                    reference.content(),
                    metadata,
                    List.of(
                            "contexte référencé explicitement depuis "
                                    + InstructionDiscoverySupport.repositoryPath(relativePath),
                            "référence locale confinée au repository")));
        }
        return List.copyOf(descriptors);
    }
}
