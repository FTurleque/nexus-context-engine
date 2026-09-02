package com.nexus.index;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Langages texte reconnus nativement par le scanner NEXUS.
 *
 * <p>La présence d'un langage ici garantit le scan, l'indexation lexicale et la
 * construction de contexte à partir du contenu. Elle ne garantit pas une
 * analyse structurelle embarquée : celle-ci reste fournie par un
 * {@link LanguageAnalyzer} spécialisé ou par un enrichissement externe tel que SCIP.</p>
 */
public enum SourceLanguage {
    JAVA("java", Set.of(".java")),
    MARKDOWN("markdown", Set.of(".md")),
    KOTLIN("kotlin", Set.of(".kt", ".kts")),
    TYPESCRIPT("typescript", Set.of(".ts", ".tsx")),
    JAVASCRIPT("javascript", Set.of(".js", ".jsx", ".mjs", ".cjs")),
    PYTHON("python", Set.of(".py")),
    SQL("sql", Set.of(".sql"));

    private final String id;
    private final Set<String> extensions;

    SourceLanguage(String id, Set<String> extensions) {
        this.id = id;
        this.extensions = Set.copyOf(extensions);
    }

    public String id() {
        return id;
    }

    public boolean supports(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return extensions.stream().anyMatch(fileName::endsWith);
    }

    public static Optional<SourceLanguage> detect(Path file) {
        return Arrays.stream(values())
                .filter(language -> language.supports(file))
                .findFirst();
    }
}
