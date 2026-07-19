package io.github.fturleque.nexus.context.source.instruction;

import io.github.fturleque.nexus.context.source.ContextSourceDescriptor;
import io.github.fturleque.nexus.context.source.ContextSourceProvider;
import io.github.fturleque.nexus.context.source.ContextSourceQuery;
import io.github.fturleque.nexus.context.source.ContextSourceScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Provider des instructions repository-wide et path-specific de GitHub Copilot.
 */
public final class CopilotInstructionProvider implements ContextSourceProvider {

    private static final String PROVIDER_ID = "github-copilot";
    private final InstructionDescriptorFactory descriptorFactory = new InstructionDescriptorFactory();

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public List<ContextSourceDescriptor> discover(ContextSourceQuery query) throws IOException {
        List<ContextSourceDescriptor> descriptors = new ArrayList<>();
        Path root = query.project().rootPath();

        Path repositoryInstructions = root.resolve(".github/copilot-instructions.md");
        if (Files.isRegularFile(repositoryInstructions)) {
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
                    true));
        }

        for (Path file : InstructionDiscoverySupport.findFilesBelow(
                query.project(), Path.of(".github", "instructions"), ".instructions.md")) {
            String content = InstructionDiscoverySupport.read(file);
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
                    false));
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
