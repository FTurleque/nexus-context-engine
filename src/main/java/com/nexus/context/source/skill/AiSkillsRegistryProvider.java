package com.nexus.context.source.skill;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Découvre les skills d'un snapshot local AI Skills Registry placé sous
 * .nexus/registry/skills dans le projet courant.
 */
public final class AiSkillsRegistryProvider implements SkillSourceProvider {

    private static final Path REGISTRY_SKILLS = Path.of(".nexus", "registry", "skills");
    private static final int PRIORITY = 60;

    private final SkillFrontmatterParser parser = new SkillFrontmatterParser();

    @Override
    public String id() {
        return "ai-skills-registry";
    }

    @Override
    public SkillProviderResult discover(SkillSourceQuery query) throws IOException {
        Path projectRoot = query.project().rootPath().toAbsolutePath().normalize();
        Path skillsRoot = projectRoot.resolve(REGISTRY_SKILLS).normalize();
        List<SkillDescriptor> skills = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        if (!Files.isDirectory(skillsRoot) || !skillsRoot.startsWith(projectRoot)) {
            return new SkillProviderResult(skills, diagnostics);
        }

        Files.walkFileTree(skillsRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!file.getFileName().toString().equalsIgnoreCase("SKILL.md")) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    SkillFrontmatter frontmatter = parser.parse(file);
                    Path absoluteSkillRoot = file.getParent().toAbsolutePath().normalize();
                    Path relativeSkillRoot = projectRoot.relativize(absoluteSkillRoot);
                    Path relativeDefinition = projectRoot.relativize(file.toAbsolutePath().normalize());
                    skills.add(new SkillDescriptor(
                            id() + ":" + repositoryPath(relativeDefinition),
                            id(),
                            frontmatter.name(),
                            frontmatter.description(),
                            relativeSkillRoot,
                            relativeDefinition,
                            frontmatter.license(),
                            frontmatter.compatibility(),
                            frontmatter.metadata(),
                            frontmatter.allowedTools(),
                            List.of(),
                            PRIORITY,
                            List.of(
                                    "Agent Skill découvert dans AI Skills Registry",
                                    "découverte progressive : frontmatter uniquement",
                                    "priorité registre inférieure aux skills locaux du projet")));
                } catch (IllegalArgumentException exception) {
                    diagnostics.add(repositoryPath(projectRoot.relativize(file))
                            + " ignoré : " + exception.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }
        });

        skills.sort(Comparator
                .comparing(SkillDescriptor::name)
                .thenComparing(skill -> repositoryPath(skill.definitionPath())));
        return new SkillProviderResult(skills, diagnostics);
    }

    private static String repositoryPath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
