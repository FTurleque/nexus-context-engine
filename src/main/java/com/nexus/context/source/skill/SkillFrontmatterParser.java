package com.nexus.context.source.skill;

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

    private final Load yaml = new Load(LoadSettings.builder()
            .setAllowDuplicateKeys(false)
            .setCodePointLimit(MAX_FRONTMATTER_CHARS)
            .build());

    SkillFrontmatter parse(Path skillFile) throws IOException {
        String frontmatter = readFrontmatter(skillFile);
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
        String compatibility = optionalString(values, "compatibility");
        if (compatibility != null) {
            validateLength("compatibility", compatibility, MAX_COMPATIBILITY_LENGTH, skillFile);
        }

        return new SkillFrontmatter(
                name,
                description,
                license,
                compatibility,
                metadata(values.get("metadata")),
                allowedTools(values.get("allowed-tools")));
    }

    private static String readFrontmatter(Path skillFile) throws IOException {
        try (BufferedReader reader = SafeFileIO.newBufferedReaderNoFollow(skillFile, StandardCharsets.UTF_8)) {
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
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Map<String, String> metadata(Object rawMetadata) {
        if (!(rawMetadata instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
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
                    .filter(value -> value != null && !String.valueOf(value).isBlank())
                    .map(String::valueOf)
                    .map(String::trim)
                    .toList();
        }

        String text = String.valueOf(rawAllowedTools).trim();
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
}
