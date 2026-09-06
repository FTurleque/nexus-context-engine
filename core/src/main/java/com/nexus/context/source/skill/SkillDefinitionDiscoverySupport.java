package com.nexus.context.source.skill;

import com.nexus.context.source.ContextDiscoveryBudget;
import com.nexus.security.ProjectPathGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/** Shared fail-closed validation and discovery-budget charging for SKILL.md files. */
final class SkillDefinitionDiscoverySupport {

    private SkillDefinitionDiscoverySupport() {
    }

    static Path validateAndCharge(
            ProjectPathGuard pathGuard,
            Path projectRoot,
            Path file,
            BasicFileAttributes attributes,
            ContextDiscoveryBudget budget,
            List<String> diagnostics) throws IOException {
        String relativePath = repositoryPath(projectRoot.relativize(file));
        if (attributes.isSymbolicLink() || Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
            diagnostics.add(relativePath + " ignoré : lien symbolique ou entrée non régulière");
            return null;
        }

        Path safeFile;
        try {
            safeFile = pathGuard.requireRegularFile(file);
        } catch (IOException unsafePath) {
            diagnostics.add(relativePath + " ignoré : " + unsafePath.getMessage());
            return null;
        }

        // The physical bytes are charged by SkillFrontmatterParser while it reads
        // through the shared discovery budget. Charging Files.size() here would
        // reintroduce a TOCTOU window and would also double-count the same content.
        budget.candidate(safeFile);
        return safeFile;
    }

    static String repositoryPath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
