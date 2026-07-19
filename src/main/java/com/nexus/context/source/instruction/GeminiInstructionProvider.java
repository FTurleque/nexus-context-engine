package com.nexus.context.source.instruction;

import com.nexus.context.source.ContextSourceDescriptor;
import com.nexus.context.source.ContextSourceProvider;
import com.nexus.context.source.ContextSourceQuery;
import com.nexus.context.source.ContextSourceScope;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Provider des fichiers GEMINI.md présents dans le repository.
 */
public final class GeminiInstructionProvider implements ContextSourceProvider {

    private static final String PROVIDER_ID = "gemini";
    private final InstructionDescriptorFactory descriptorFactory = new InstructionDescriptorFactory();

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public List<ContextSourceDescriptor> discover(ContextSourceQuery query) throws IOException {
        List<ContextSourceDescriptor> descriptors = new ArrayList<>();
        for (Path file : InstructionDiscoverySupport.findNamedFiles(query.project(), Set.of("GEMINI.md"))) {
            Path relative = InstructionDiscoverySupport.relative(query.project(), file);
            if (!InstructionDiscoverySupport.directoryScopeApplies(relative, query.targetPaths())) {
                continue;
            }
            int depth = InstructionDiscoverySupport.directoryDepth(relative);
            boolean repositoryWide = relative.getParent() == null;
            descriptors.addAll(descriptorFactory.create(
                    query.project(),
                    PROVIDER_ID,
                    "GEMINI_MD",
                    file,
                    repositoryWide ? ContextSourceScope.REPOSITORY : ContextSourceScope.DIRECTORY_TREE,
                    List.of(),
                    Math.min(90, 70 + depth * 3),
                    List.of(
                            repositoryWide
                                    ? "instructions GEMINI applicables au repository"
                                    : "instructions GEMINI imbriquées applicables au chemin cible",
                            repositoryWide
                                    ? "scope repository"
                                    : "scope répertoire : "
                                        + InstructionDiscoverySupport.repositoryPath(relative.getParent())),
                    false));
        }
        return List.copyOf(descriptors);
    }
}
