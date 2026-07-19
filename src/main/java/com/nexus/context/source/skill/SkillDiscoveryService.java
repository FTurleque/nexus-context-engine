package com.nexus.context.source.skill;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agrège plusieurs providers de skills sans charger leur contenu complet.
 */
public final class SkillDiscoveryService {

    public SkillDiscoveryResult discover(
            List<SkillSourceProvider> providers,
            SkillSourceQuery query) throws IOException {
        List<SkillDescriptor> discovered = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        for (SkillSourceProvider provider : providers) {
            SkillProviderResult providerResult = provider.discover(query);
            discovered.addAll(providerResult.skills());
            diagnostics.addAll(providerResult.diagnostics());
        }

        List<SkillDescriptor> sorted = discovered.stream()
                .sorted(Comparator
                        .comparingInt(SkillDescriptor::priority).reversed()
                        .thenComparing(SkillDescriptor::name)
                        .thenComparing(skill -> repositoryPath(skill.definitionPath()))
                        .thenComparing(SkillDescriptor::provider))
                .toList();

        Map<String, SkillDescriptor> byName = new LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();
        for (SkillDescriptor skill : sorted) {
            String key = skill.name().toLowerCase(Locale.ROOT);
            SkillDescriptor existing = byName.putIfAbsent(key, skill);
            if (existing != null) {
                duplicates.add(repositoryPath(skill.definitionPath())
                        + " dédupliqué avec "
                        + repositoryPath(existing.definitionPath())
                        + " pour le skill '" + skill.name() + "'");
                if (!existing.description().equals(skill.description())) {
                    diagnostics.add("Conflit de descriptions pour le skill '"
                            + skill.name() + "' : "
                            + repositoryPath(existing.definitionPath()) + " conservé, "
                            + repositoryPath(skill.definitionPath()) + " ignoré");
                }
            }
        }

        return new SkillDiscoveryResult(
                List.copyOf(byName.values()),
                duplicates,
                diagnostics);
    }

    private static String repositoryPath(java.nio.file.Path path) {
        return path.toString().replace('\\', '/');
    }
}
