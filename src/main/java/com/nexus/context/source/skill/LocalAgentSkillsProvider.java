package com.nexus.context.source.skill;

import com.nexus.context.source.ContextDiscoveryBudget;
import com.nexus.index.scan.ProjectIgnoreMatcher;
import com.nexus.security.ProjectPathGuard;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Provider local des Agent Skills versionnés dans le repository. */
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
        ProjectPathGuard pathGuard = new ProjectPathGuard(query.project().rootPath());
        Path projectRoot = pathGuard.root();
        ProjectIgnoreMatcher ignoreMatcher = new ProjectIgnoreMatcher(projectRoot);
        List<SkillDescriptor> skills = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        for (String relativeRoot : SKILL_ROOTS) {
            query.discoveryBudget().checkpoint();
            Path candidate = pathGuard.resolve(Path.of(relativeRoot));
            Path skillContainer;
            try {
                skillContainer = pathGuard.requireDirectory(candidate);
            } catch (IOException missingOrUnsafe) {
                if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(candidate)) {
                    diagnostics.add(relativeRoot + " ignoré : " + missingOrUnsafe.getMessage());
                }
                continue;
            }
            registerParentChain(projectRoot, skillContainer, ignoreMatcher);
            discoverBelow(
                    pathGuard,
                    projectRoot,
                    skillContainer,
                    relativeRoot,
                    ignoreMatcher,
                    skills,
                    diagnostics,
                    query.discoveryBudget());
        }

        skills.sort(Comparator
                .comparing(SkillDescriptor::name)
                .thenComparing(skill -> repositoryPath(skill.definitionPath())));
        return new SkillProviderResult(skills, diagnostics);
    }

    private void discoverBelow(
            ProjectPathGuard pathGuard,
            Path projectRoot,
            Path skillContainer,
            String originRoot,
            ProjectIgnoreMatcher ignoreMatcher,
            List<SkillDescriptor> skills,
            List<String> diagnostics,
            ContextDiscoveryBudget budget) throws IOException {
        Files.walkFileTree(skillContainer, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                budget.visit(directory);
                if (!directory.equals(skillContainer) && ignoreMatcher.isIgnored(directory, true)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                ignoreMatcher.registerDirectory(directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                budget.visit(file);
                if (!file.getFileName().toString().equalsIgnoreCase("SKILL.md")
                        || ignoreMatcher.isIgnored(file, false)) {
                    return FileVisitResult.CONTINUE;
                }
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    diagnostics.add(repositoryPath(projectRoot.relativize(file))
                            + " ignoré : lien symbolique ou entrée non régulière");
                    return FileVisitResult.CONTINUE;
                }

                Path safeFile;
                try {
                    safeFile = pathGuard.requireRegularFile(file);
                } catch (IOException unsafePath) {
                    diagnostics.add(repositoryPath(projectRoot.relativize(file))
                            + " ignoré : " + unsafePath.getMessage());
                    return FileVisitResult.CONTINUE;
                }

                long chargedBytes;
                try {
                    chargedBytes = Math.min(Files.size(safeFile), SkillFrontmatterParser.MAX_DISCOVERY_BYTES);
                } catch (IOException unreadable) {
                    diagnostics.add(repositoryPath(projectRoot.relativize(file))
                            + " ignoré : " + unreadable.getMessage());
                    return FileVisitResult.CONTINUE;
                }
                budget.candidate(safeFile);
                budget.bytes(safeFile, chargedBytes);

                try {
                    SkillFrontmatter frontmatter = parser.parse(safeFile);
                    Path absoluteSkillRoot = safeFile.getParent();
                    Path relativeSkillRoot = projectRoot.relativize(absoluteSkillRoot);
                    Path relativeDefinition = projectRoot.relativize(safeFile);
                    List<SkillResourceDescriptor> resources = resources(
                            pathGuard,
                            projectRoot,
                            absoluteSkillRoot,
                            safeFile,
                            ignoreMatcher,
                            budget);
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
                } catch (IllegalArgumentException | IOException exception) {
                    diagnostics.add(repositoryPath(projectRoot.relativize(file))
                            + " ignoré : " + exception.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static List<SkillResourceDescriptor> resources(
            ProjectPathGuard pathGuard,
            Path projectRoot,
            Path skillRoot,
            Path definitionFile,
            ProjectIgnoreMatcher ignoreMatcher,
            ContextDiscoveryBudget budget) throws IOException {
        List<SkillResourceDescriptor> resources = new ArrayList<>();
        Files.walkFileTree(skillRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                budget.visit(directory);
                if (!directory.equals(skillRoot) && ignoreMatcher.isIgnored(directory, true)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                ignoreMatcher.registerDirectory(directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                budget.visit(file);
                if (file.equals(definitionFile)
                        || ignoreMatcher.isIgnored(file, false)
                        || attributes.isSymbolicLink()
                        || Files.isSymbolicLink(file)
                        || !attributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                Path safeFile;
                try {
                    safeFile = pathGuard.requireRegularFile(file);
                } catch (IOException unsafePath) {
                    return FileVisitResult.CONTINUE;
                }
                budget.candidate(safeFile);
                Path relativeToSkill = skillRoot.relativize(safeFile);
                resources.add(new SkillResourceDescriptor(
                        projectRoot.relativize(safeFile),
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
