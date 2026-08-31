package com.nexus.context.source.instruction;

import com.nexus.context.source.ContextSourceDescriptor;
import com.nexus.context.source.ContextSourceProvider;
import com.nexus.context.source.ContextSourceQuery;
import com.nexus.context.source.ContextSourceScope;
import com.nexus.security.ProjectPathGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Provider des instructions repository-wide et path-specific de GitHub Copilot.
 */
public final class CopilotInstructionProvider implements ContextSourceProvider {

    private static final String PROVIDER_ID = "github-copilot";
    private static final Path REPOSITORY_INSTRUCTIONS = Path.of(".github", "copilot-instructions.md");
    private final InstructionDescriptorFactory descriptorFactory = new InstructionDescriptorFactory();

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public List<ContextSourceDescriptor> discover(ContextSourceQuery query) throws IOException {
        List<ContextSourceDescriptor> descriptors = new ArrayList<>();
        ProjectPathGuard pathGuard = new ProjectPathGuard(query.project().rootPath());
        Path repositoryCandidate = pathGuard.resolve(REPOSITORY_INSTRUCTIONS);
        if (Files.exists(repositoryCandidate, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(repositoryCandidate)) {
            query.discoveryBudget().visit(repositoryCandidate);
            Path repositoryInstructions = pathGuard.requireRegularFile(repositoryCandidate);
            query.discoveryBudget().candidate(repositoryInstructions);
            descriptors.addAll(descriptorFactory.create(
                    query.project(),
                    PROVIDER_ID,
                    "COPILOT_REPOSITORY",
                    repositoryInstructions,
                    ContextSourceScope.REPOSITORY,
                    List.of(),
                    78,
                    List.of(
                            "instructions GitHub Copilot applicables au repository",
                            "scope repository"),
                    true,
                    query.discoveryBudget()));
        }

        for (Path file : InstructionDiscoverySupport.findFilesBelow(
                query.project(),
                Path.of(".github", "instructions"),
                ".instructions.md",
                query.discoveryBudget())) {
            String content = InstructionDiscoverySupport.read(query.project(), file, query.discoveryBudget());
            List<String> applyTo = parseApplyTo(content);
            if (applyTo.isEmpty() || !RepositoryGlobMatcher.matchesAny(applyTo, query.targetPaths())) {
                continue;
            }
            descriptors.addAll(descriptorFactory.create(
                    query.project(),
                    PROVIDER_ID,
                    "COPILOT_PATH_SPECIFIC",
                    file,
                    ContextSourceScope.PATH_GLOB,
                    applyTo,
                    96,
                    List.of(
                            "instruction GitHub Copilot path-specific applicable",
                            "applyTo : " + String.join(", ", applyTo),
                            "priorité élevée par spécificité de chemin"),
                    false,
                    query.discoveryBudget()));
        }
        return List.copyOf(descriptors);
    }

    private static List<String> parseApplyTo(String content) {
        String[] lines = content.split("\\R", -1);
        if (lines.length == 0 || !lines[0].trim().equals("---")) {
            return List.of();
        }

        for (int index = 1; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.equals("---")) {
                break;
            }
            if (!line.startsWith("applyTo:")) {
                continue;
            }
            String value = line.substring("applyTo:".length()).trim();
            value = stripQuotesAndBrackets(value);
            if (value.isBlank()) {
                return List.of();
            }
            return Arrays.stream(value.split(","))
                    .map(String::trim)
                    .map(CopilotInstructionProvider::stripQuotesAndBrackets)
                    .filter(pattern -> !pattern.isBlank())
                    .toList();
        }
        return List.of();
    }

    private static String stripQuotesAndBrackets(String value) {
        String result = value.trim();
        if (result.startsWith("[") && result.endsWith("]")) {
            result = result.substring(1, result.length() - 1).trim();
        }
        if ((result.startsWith("\"") && result.endsWith("\""))
                || (result.startsWith("'") && result.endsWith("'"))) {
            result = result.substring(1, result.length() - 1);
        }
        return result.trim();
    }
}
