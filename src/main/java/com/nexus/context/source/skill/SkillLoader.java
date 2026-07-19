package com.nexus.context.source.skill;

import com.nexus.project.ProjectDescriptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Charge le contenu complet uniquement pour les skills déjà sélectionnés.
 */
public final class SkillLoader {

    public SkillActivationResult load(
            ProjectDescriptor project,
            List<SkillMatch> selectedSkills) throws IOException {
        Path root = project.rootPath().toAbsolutePath().normalize();
        List<ActivatedSkill> activated = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        for (SkillMatch match : selectedSkills) {
            Path definition = root.resolve(match.skill().definitionPath()).normalize();
            if (!definition.startsWith(root)) {
                diagnostics.add(match.skill().name() + " non chargé : chemin hors repository");
                continue;
            }
            if (!Files.isRegularFile(definition)) {
                diagnostics.add(match.skill().name() + " non chargé : SKILL.md introuvable");
                continue;
            }

            String content = Files.readString(definition, StandardCharsets.UTF_8);
            List<String> reasons = new ArrayList<>(match.reasons());
            reasons.add("SKILL.md complet chargé après sélection des métadonnées");
            if (!match.skill().resources().isEmpty()) {
                reasons.add(match.skill().resources().size()
                        + " ressource(s) associée(s) inventoriée(s), non chargée(s) automatiquement");
            }
            reasons.add("NEXUS référence le skill sans exécuter ses scripts");
            activated.add(new ActivatedSkill(
                    match.skill(),
                    match.score(),
                    content,
                    reasons));
        }

        return new SkillActivationResult(activated, diagnostics);
    }
}
