package com.nexus.context.source.skill;

import com.nexus.context.source.ContextDiscoveryBudget;
import com.nexus.security.SafeFileIO;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Lit uniquement le frontmatter YAML d'un SKILL.md.
 */
final class SkillFrontmatterParser {

    private static final Pattern VALID_NAME = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_DESCRIPTION_LENGTH = 1_024;
    private static final int MAX_COMPATIBILITY_LENGTH = 500;
    private static final int MAX_FRONTMATTER_CHARS = 65_536;
    static final long MAX_DISCOVERY_BYTES = 4L * MAX_FRONTMATTER_CHARS + 4_096L;

    private final Load yaml = new Load(LoadSettings.builder()
            .setAllowDuplicateKeys(false)
            .setMaxAliasesForCollections(0)
            .setCodePointLimit(MAX_FRONTMATTER_CHARS)
            .build());

    SkillFrontmatter parse(Path skillFile) throws IOException {
        return parseFrontmatter(skillFile, readFrontmatter(skillFile, null));
    }

    SkillFrontmatter parse(Path skillFile, ContextDiscoveryBudget budget) throws IOException {
        return parseFrontmatter(skillFile, readFrontmatter(skillFile, budget));
    }

    private SkillFrontmatter parseFrontmatter(Path skillFile, String frontmatter) {
        Object loaded;
        try {
            loaded = yaml.loadFromString(frontmatter);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Frontmatter YAML invalide dans " + skillFile, exception);
        }
        if (!(loaded instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Le frontmatter de " + skillFile + " doit être un objet YAML");
        }

        String name = requiredString(values, "name");
        String description = requiredString(values, "description");
        validateName(skillFile, name);
        validateLength("description", description, MAX_DESCRIPTION_LENGTH, skillFile);

        String license = optionalString(values, "license");
        String compatibility = compatibility(values);
        if (compatibility != null) {
            validateLength("compatibility", compatibility, MAX_COMPATIBILITY_LENGTH, skillFile);
        }

        SkillFrontmatter result = new SkillFrontmatter(
                name,
                description,
                license,
                compatibility,
                metadata(values.get("metadata")),
                allowedTools(values.get("allowed-tools")));
        validateDecodedSize(result);
        return result;
    }

    private static String readFrontmatter(Path skillFile, ContextDiscoveryBudget budget) throws IOException {
        // The frontmatter parser keeps its own physical-byte ceiling. When it is
        // called from native discovery, the same bytes are additionally charged
        // against the shared cumulative budget while they are physically read.
        try (BufferedReader reader = budget == null
                ? SafeFileIO.newBufferedReaderNoFollow(
                        skillFile,
                        StandardCharsets.UTF_8,
                        MAX_DISCOVERY_BYTES)
                : budget.newBufferedReaderNoFollow(
                        skillFile,
                        StandardCharsets.UTF_8,
                        MAX_DISCOVERY_BYTES)) {
            String firstLine = reader.readLine();
            if (!"---".equals(firstLine)) {
                throw new IllegalArgumentException("SKILL.md doit commencer par un frontmatter YAML : " + skillFile);
            }

            StringBuilder yaml = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if ("---".equals(line)) {
                    return yaml.toString();
                }
                if (yaml.length() + line.length() + 1 > MAX_FRONTMATTER_CHARS) {
                    throw new IllegalArgumentException(
                            "Frontmatter YAML trop volumineux dans " + skillFile
                                    + " (maximum " + MAX_FRONTMATTER_CHARS + " caractères)");
                }
                yaml.append(line).append('\n');
            }
            throw new IllegalArgumentException("Frontmatter YAML non terminé dans " + skillFile);
        }
    }

    private static void validateName(Path skillFile, String name) {
        if (name.length() > MAX_NAME_LENGTH || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Nom de skill invalide dans " + skillFile
                            + " : utiliser 1-64 caractères [a-z0-9-] sans tirets consécutifs");
        }
        Path parent = skillFile.getParent();
        String directoryName = parent == null ? "" : parent.getFileName().toString();
        if (!name.equals(directoryName)) {
            throw new IllegalArgumentException(
                    "Le nom du skill '" + name + "' doit correspondre au dossier parent '"
                            + directoryName + "' dans " + skillFile);
        }
    }

    private static void validateLength(String field, String value, int maximum, Path skillFile) {
        if (value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(
                    "Champ " + field + " invalide dans " + skillFile + " (maximum " + maximum + " caractères)");
        }
    }

    private static String requiredString(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Champ obligatoire manquant ou vide : " + key);
        }
        return text.trim();
    }

    private static String optionalString(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        String text = stringValue(value, key).trim();
        return text.isEmpty() ? null : text;
    }

    private static Map<String, String> metadata(Object rawMetadata) {
        if (rawMetadata == null) {
            return Map.of();
        }
        if (!(rawMetadata instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Le champ metadata doit être un objet de chaînes");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(stringValue(entry.getKey(), "metadata key"), stringValue(entry.getValue(), "metadata value"));
            }
        }
        return Map.copyOf(result);
    }

    private static List<String> allowedTools(Object rawAllowedTools) {
        if (rawAllowedTools == null) {
            return List.of();
        }
        if (rawAllowedTools instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(value -> stringValue(value, "allowed-tools"))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .toList();
        }

        String text = stringValue(rawAllowedTools, "allowed-tools").trim();
        if (text.isEmpty()) {
            return List.of();
        }
        String[] parts = text.split("\\s+");
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!part.isBlank()) {
                result.add(part);
            }
        }
        return List.copyOf(result);
    }

    private static String compatibility(Map<?, ?> values) {
        if (!(values.get("compatibility") instanceof List<?> list)) {
            return optionalString(values, "compatibility");
        }
        // The AI skills registry also accepts a flat list of client names.
        // Check every element and its cumulative size before joining it.
        StringBuilder text = new StringBuilder("[");
        for (Object value : list) {
            String item = stringValue(value, "compatibility");
            int separatorLength = text.length() > 1 ? 2 : 0;
            if ((long) text.length() + separatorLength + item.length() + 1 > MAX_COMPATIBILITY_LENGTH) {
                throw new IllegalArgumentException("Champ compatibility trop volumineux");
            }
            if (separatorLength > 0) {
                text.append(", ");
            }
            text.append(item);
        }
        return text.append(']').toString();
    }

    private static String stringValue(Object value, String field) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Le champ " + field + " doit contenir une chaîne");
        }
        return text;
    }

    private static void validateDecodedSize(SkillFrontmatter value) {
        long characters = (long) value.name().length() + value.description().length();
        characters += value.license() == null ? 0 : value.license().length();
        characters += value.compatibility() == null ? 0 : value.compatibility().length();
        for (Map.Entry<String, String> entry : value.metadata().entrySet()) {
            characters += (long) entry.getKey().length() + entry.getValue().length();
        }
        for (String tool : value.allowedTools()) {
            characters += tool.length();
        }
        if (characters > MAX_FRONTMATTER_CHARS) {
            throw new IllegalArgumentException("Métadonnées de skill décodées trop volumineuses (maximum "
                    + MAX_FRONTMATTER_CHARS + " caractères)");
        }
    }
}
