package com.nexus.index.markdown;

import com.nexus.index.AnalysisResult;
import com.nexus.index.LanguageAnalyzer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Analyseur minimal pour les fichiers Markdown.
 *
 * <p>L'Itération 5 indexe le contenu Markdown dans Lucene mais ne produit pas
 * encore de symboles structurels pour les titres ou ancres. Cette classe garde
 * néanmoins le pipeline d'indexation homogène derrière {@link LanguageAnalyzer}.</p>
 */
public final class MarkdownLanguageAnalyzer implements LanguageAnalyzer {

    @Override
    public boolean supports(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md");
    }

    @Override
    public AnalysisResult analyze(Path projectRoot, Path file) throws IOException {
        return new AnalysisResult(file, "markdown", List.of(), List.of());
    }
}
