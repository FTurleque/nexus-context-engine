package com.nexus.context.source.skill;

import com.nexus.index.scan.ProjectIgnoreMatcher;

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
 * Provider local des Agent Skills versionnés dans le repository.
 */
public final class LocalAgentSkillsProvider implements SkillSourceProvider {

    private static final List<String> SKILL_ROOTS = List.of(
            ".agents/skills",
            ".github/skills",
            ".claude/skills");
    private static final int DEFAULT_PRIORITY = 80;

    private final SkillFrontmatterParser parser = new SkillFrontmatterParser();

    @Override
    public String id() {
        return "local-agent-skills";
    }

    @Override
    public SkillProviderResult discover(SkillSourceQuery query) throws IOException {
        Path projectRoot = query.project().rootPath().toAbsolutePath().normalize();
        ProjectIgnoreMatcher ignoreMatcher = new ProjectIgnoreMatcher(projectRoot);
        List<SkillDescriptor> skills = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        for (String relativeRoot : SKILL_ROOTS) {
            Path skillContainer = projectRoot.resolve(relativeRoot).normalize();
            if (!Files.isDirectory(skillContainer) || !skillContainer.startsWith(projectRoot)) {
                continue;
            }
            registerParentChain(projectRoot, skillContainer, ignoreMatcher);
            discoverBelow(projectRoot, skillContainer, relativeRoot, ignoreMatcher, skills, diagnostics);
        }

        skills.sort(Comparator
                .comparing(SkillDescriptor::name)
                .thenComparing(skill -> repositoryPath(skill.definitionPath())));
        return new SkillProviderResult(skills, diagnostics);
    }

    private void discoverBelow(
            Path projectRoot,
            Path skillContainer,
            String originRoot,
            ProjectIgnoreMatcher ignoreMatcher,
            List<SkillDescriptor> skills,
            List<String> diagnostics) throws IOException {
        Files.walkFileTree(skillContainer, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (!directory.equals(skillContainer) && ignoreMatcher.isIgnored(directory, true)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                ignoreMatcher.registerDirectory(directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!file.getFileName().toString().equalsIgnoreCase("SKILL.md")
                        || ignoreMatcher.isIgnored(file, false)) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    SkillFrontmatter frontmatter = parser.parse(file);
                    Path absoluteSkillRoot = file.getParent().toAbsolutePath().normalize();
                    Path relativeSkillRoot = projectRoot.relativize(absoluteSkillRoot);
                    Path relativeDefinition = projectRoot.relativize(file.toAbsolutePath().normalize());
                    List<SkillResourceDescriptor> resources = resources(
                            projectRoot,
                            absoluteSkillRoot,
                            file,
                            ignoreMatcher);
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
                            resources,
                            DEFAULT_PRIORITY,
                            List.of(
                                    "Agent Skill découvert dans " + originRoot,
                                    "découverte progressive : frontmatter uniquement")));
                } catch (IllegalArgumentException exception) {
                    diagnostics.add(repositoryPath(projectRoot.relativize(file))
                            + " ignoré : " + exception.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static List<SkillResourceDescriptor> resources(
            Path projectRoot,
            Path skillRoot,
            Path definitionFile,
            ProjectIgnoreMatcher ignoreMatcher) throws IOException {
        List<SkillResourceDescriptor> resources = new ArrayList<>();
        Files.walkFileTree(skillRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (!directory.equals(skillRoot) && ignoreMatcher.isIgnored(directory, true)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                ignoreMatcher.registerDirectory(directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (file.equals(definitionFile) || ignoreMatcher.isIgnored(file, false)) {
                    return FileVisitResult.CONTINUE;
                }
                Path relativeToSkill = skillRoot.relativize(file);
                resources.add(new SkillResourceDescriptor(
                        projectRoot.relativize(file),
                        resourceType(relativeToSkill),
                        attributes.size()));
                return FileVisitResult.CONTINUE;
            }
        });
        resources.sort(Comparator.comparing(resource -> repositoryPath(resource.path())));
        return List.copyOf(resources);
    }

    private static SkillResourceType resourceType(Path relativeToSkill) {
        if (relativeToSkill.getNameCount() == 0) {
            return SkillResourceType.OTHER;
        }
        return switch (relativeToSkill.getName(0).toString().toLowerCase()) {
            case "scripts" -> SkillResourceType.SCRIPT;
            case "references" -> SkillResourceType.REFERENCE;
            case "assets" -> SkillResourceType.ASSET;
            default -> SkillResourceType.OTHER;
        };
    }

    private static void registerParentChain(
            Path projectRoot,
            Path target,
            ProjectIgnoreMatcher ignoreMatcher) throws IOException {
        Path current = projectRoot;
        for (Path part : projectRoot.relativize(target)) {
            current = current.resolve(part);
            ignoreMatcher.registerDirectory(current);
        }
    }

    private static String repositoryPath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
