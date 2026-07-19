package io.github.fturleque.nexus.context.source.instruction;

import io.github.fturleque.nexus.context.source.ContextSourceDescriptor;
import io.github.fturleque.nexus.context.source.ContextSourceProvider;
import io.github.fturleque.nexus.context.source.ContextSourceQuery;
import io.github.fturleque.nexus.context.source.ContextSourceScope;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Provider des fichiers CLAUDE.md utilisés comme mémoire/instructions projet.
 */
public final class ClaudeInstructionProvider implements ContextSourceProvider {

    private static final String PROVIDER_ID = "claude";
    private final InstructionDescriptorFactory descriptorFactory = new InstructionDescriptorFactory();

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public List<ContextSourceDescriptor> discover(ContextSourceQuery query) throws IOException {
        List<ContextSourceDescriptor> descriptors = new ArrayList<>();
        for (Path file : InstructionDiscoverySupport.findNamedFiles(query.project(), Set.of("CLAUDE.md"))) {
            Path relative = InstructionDiscoverySupport.relative(query.project(), file);
            boolean claudeDirectoryRoot = InstructionDiscoverySupport.repositoryPath(relative)
                    .equalsIgnoreCase(".claude/CLAUDE.md");
            boolean repositoryWide = relative.getParent() == null || claudeDirectoryRoot;
            if (!repositoryWide
                    && !InstructionDiscoverySupport.directoryScopeApplies(relative, query.targetPaths())) {
                continue;
            }

            int depth = repositoryWide ? 0 : InstructionDiscoverySupport.directoryDepth(relative);
            int priority = repositoryWide ? 80 : Math.min(94, 80 + depth * 3);
            descriptors.addAll(descriptorFactory.create(
                    query.project(),
                    PROVIDER_ID,
                    claudeDirectoryRoot ? "CLAUDE_DOT_DIRECTORY" : "CLAUDE_MD",
                    file,
                    repositoryWide ? ContextSourceScope.REPOSITORY : ContextSourceScope.DIRECTORY_TREE,
                    List.of(),
                    priority,
                    List.of(
                            repositoryWide
                                    ? "instructions Claude applicables au repository"
                                    : "instructions Claude imbriquées applicables au chemin cible",
                            repositoryWide
                                    ? "scope repository"
                                    : "scope répertoire : "
                                        + InstructionDiscoverySupport.repositoryPath(relative.getParent())),
                    true));
        }
        return List.copyOf(descriptors);
    }
}
