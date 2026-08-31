package com.nexus.context.source.skill;

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
        ProjectPathGuard pathGuard = new ProjectPathGuard(query.project().rootPath());
        Path projectRoot = pathGuard.root();
        Path candidate = pathGuard.resolve(REGISTRY_SKILLS);
        List<SkillDescriptor> skills = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        Path skillsRoot;
        try {
            skillsRoot = pathGuard.requireDirectory(candidate);
        } catch (IOException missingOrUnsafe) {
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(candidate)) {
                diagnostics.add(repositoryPath(REGISTRY_SKILLS) + " ignoré : " + missingOrUnsafe.getMessage());
            }
            return new SkillProviderResult(skills, diagnostics);
        }

        Files.walkFileTree(skillsRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                query.discoveryBudget().visit(directory);
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(directory)) {
                    diagnostics.add(repositoryPath(projectRoot.relativize(directory))
                            + " ignoré : lien symbolique interdit");
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                query.discoveryBudget().visit(file);
                if (!file.getFileName().toString().equalsIgnoreCase("SKILL.md")) {
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
                query.discoveryBudget().candidate(safeFile);
                query.discoveryBudget().bytes(safeFile, chargedBytes);

                try {
                    SkillFrontmatter frontmatter = parser.parse(safeFile);
                    Path absoluteSkillRoot = safeFile.getParent();
                    Path relativeSkillRoot = projectRoot.relativize(absoluteSkillRoot);
                    Path relativeDefinition = projectRoot.relativize(safeFile);
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
                } catch (IllegalArgumentException | IOException exception) {
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
