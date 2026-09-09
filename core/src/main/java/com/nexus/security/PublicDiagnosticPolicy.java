package com.nexus.security;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Projection des diagnostics internes aux frontières publiques. Ne doit pas être
 * appliquée aux requêtes du client ni au corps source (redigé avant estimation).
 * Les objets inconnus sont refusés, jamais sérialisés via leur toString().
 */
public final class PublicDiagnosticPolicy {
    // Aucun délimiteur ne permet de terminer sûrement un chemin dans du texte libre.
    // Dès détection d'un chemin inconnu, masquer le message complet évite les suffixes.
    private static final Pattern ABSOLUTE = Pattern.compile(
            "(?<![\\p{L}\\p{N}_./\\\\])(?:[A-Za-z]:[/\\\\]|\\\\\\\\|/)");
    private static final int MAX_DEPTH = 16;
    private static final int MAX_VALUES = 100_000;
    private static final int MAX_TEXT = 1_048_576;
    private static final Pattern FILE_URI = Pattern.compile("(?i)file:[^\\s\"'<>|,;()]*");
    private final List<Pattern> roots;

    public PublicDiagnosticPolicy(List<Path> projectRoots) {
        roots = projectRoots.stream().map(root -> {
            boolean windows = root.getFileSystem().getSeparator().equals("\\");
            String normalized = root.toAbsolutePath().normalize().toString();
            if (windows) normalized = normalized.replace('\\', '/');
            String expression = windows ? Pattern.quote(normalized).replace("/", "\\E[/\\\\]\\Q")
                    : Pattern.quote(normalized);
            int flags = windows ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
            String separator = windows ? "[/\\\\]" : "/";
            return Pattern.compile("(?<![\\p{L}\\p{N}_])" + expression
                    + "(?=" + separator + "|$)" + separator + "?", flags);
        }).toList();
    }

    public static PublicDiagnosticPolicy internal() {
        return new PublicDiagnosticPolicy(List.of());
    }

    public String text(String value) {
        if (value == null) return null;
        if (value.length() > MAX_TEXT) throw new IllegalStateException("Diagnostic public trop volumineux");
        if (FILE_URI.matcher(value).find()) return "[INTERNAL_PATH]";
        String result = value;
        for (Pattern root : roots) result = root.matcher(result).replaceAll(Matcher.quoteReplacement("./"));
        if (ABSOLUTE.matcher(result).find() || result.contains("/../") || result.contains("\\..\\")) return "[INTERNAL_PATH]";
        return SensitiveContentRedactor.redact(result);
    }

    /** Les causes libres restent internes ; seuls code, catégorie et chemin validé sortent. */
    public record Diagnostic(String code, String message, Path repositoryPath, String causeCategory) { }

    public String render(Diagnostic diagnostic, Path projectRoot) {
        String path;
        try {
            path = PublicProjectPathPolicy.expose(projectRoot, diagnostic.repositoryPath());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            path = "[INTERNAL_PATH]";
        }
        return text(diagnostic.code()) + ": " + text(diagnostic.message()) + " : " + path
                + " (" + text(diagnostic.causeCategory()) + ")";
    }

    public List<String> texts(List<String> values) {
        if (values.size() > MAX_VALUES) throw new IllegalStateException("Trop de diagnostics publics");
        return values.stream().map(this::text).toList();
    }

    public Map<String, Object> metadata(Map<String, Object> values) {
        return map(values, 0, new int[]{0});
    }

    private Map<String, Object> map(Map<?, ?> values, int depth, int[] count) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw unsupported();
            String safeKey = text(key);
            if (result.containsKey(safeKey)) throw unsupported();
            result.put(safeKey, value(entry.getValue(), depth + 1, count));
        }
        return Collections.unmodifiableMap(result);
    }

    private Object value(Object value, int depth, int[] count) {
        if (depth > MAX_DEPTH || ++count[0] > MAX_VALUES) throw unsupported();
        if (value == null || value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) return value;
        if (value instanceof Double d && Double.isFinite(d)) return value;
        if (value instanceof Float f && Float.isFinite(f)) return value;
        if (value instanceof String string) return text(string);
        if (value instanceof Path path) return text(path.toString());
        if (value instanceof Map<?, ?> map) return map(map, depth, count);
        if (value instanceof Iterable<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object element : list) result.add(value(element, depth + 1, count));
            return Collections.unmodifiableList(result);
        }
        // Ces records ne contiennent que des compteurs numériques validés. Garder
        // leur type préserve aussi le contrat core des snapshots de budgets.
        if (value instanceof com.nexus.context.source.ContextDiscoveryLimits
                || value instanceof com.nexus.context.source.ContextDiscoveryBudget.Snapshot
                || value instanceof com.nexus.context.ContextMaterializationLimits
                || value instanceof com.nexus.context.ContextMaterializationBudget.Snapshot) return value;
        throw unsupported();
    }

    private static IllegalStateException unsupported() {
        return new IllegalStateException("Structure de diagnostic public non supportée ou trop volumineuse");
    }
}
