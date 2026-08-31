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
 * Provider des conventions AGENTS.md et de l'alias de compatibilité AGENT.md.
 */
public final class AgentsMdInstructionProvider implements ContextSourceProvider {

    private static final String PROVIDER_ID = "agents-md";
    private final InstructionDescriptorFactory descriptorFactory = new InstructionDescriptorFactory();

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public List<ContextSourceDescriptor> discover(ContextSourceQuery query) throws IOException {
        List<ContextSourceDescriptor> descriptors = new ArrayList<>();
        for (Path file : InstructionDiscoverySupport.findNamedFiles(
                query.project(), Set.of("AGENTS.md", "AGENT.md"), query.discoveryBudget())) {
            Path relative = InstructionDiscoverySupport.relative(query.project(), file);
            if (!InstructionDiscoverySupport.directoryScopeApplies(relative, query.targetPaths())) {
                continue;
            }

            boolean alias = file.getFileName().toString().equalsIgnoreCase("AGENT.md");
            int depth = InstructionDiscoverySupport.directoryDepth(relative);
            int priority = Math.min(94, (alias ? 64 : 74) + depth * 4);
            ContextSourceScope scope = relative.getParent() == null
                    ? ContextSourceScope.REPOSITORY
                    : ContextSourceScope.DIRECTORY_TREE;
            List<String> reasons = new ArrayList<>();
            reasons.add(alias
                    ? "instruction AGENT.md découverte comme alias de compatibilité"
                    : "instruction AGENTS.md applicable au chemin cible");
            reasons.add(scope == ContextSourceScope.REPOSITORY
                    ? "scope repository"
                    : "scope répertoire : " + InstructionDiscoverySupport.repositoryPath(relative.getParent()));
            if (depth > 0) {
                reasons.add("priorité augmentée par la proximité avec le fichier cible");
            }

            descriptors.addAll(descriptorFactory.create(
                    query.project(),
                    PROVIDER_ID,
                    alias ? "AGENT_MD_COMPAT" : "AGENTS_MD",
                    file,
                    scope,
                    List.of(),
                    priority,
                    reasons,
                    true,
                    query.discoveryBudget()));
        }
        return List.copyOf(descriptors);
    }
}
