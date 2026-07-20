package com.nexus.index.text;

import com.nexus.index.AnalysisResult;
import com.nexus.index.LanguageAnalyzer;
import com.nexus.index.SourceLanguage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Analyseur structurel neutre pour les langages pris en charge lexicalement.
 *
 * <p>Il permet d'intégrer le contenu dans le pipeline NEXUS sans inventer de
 * symboles ou relations approximatifs. Les symboles multi-langages peuvent
 * ensuite être enrichis par un index SCIP ou un futur analyseur spécialisé.</p>
 */
public final class PlainTextSourceLanguageAnalyzer implements LanguageAnalyzer {

    private static final Set<SourceLanguage> SUPPORTED_LANGUAGES = EnumSet.of(
            SourceLanguage.KOTLIN,
            SourceLanguage.TYPESCRIPT,
            SourceLanguage.JAVASCRIPT,
            SourceLanguage.PYTHON,
            SourceLanguage.SQL);

    @Override
    public boolean supports(Path file) {
        Objects.requireNonNull(file, "file");
        return SourceLanguage.detect(file)
                .filter(SUPPORTED_LANGUAGES::contains)
                .isPresent();
    }

    @Override
    public AnalysisResult analyze(Path projectRoot, Path file) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(file, "file");
        SourceLanguage language = SourceLanguage.detect(file)
                .filter(SUPPORTED_LANGUAGES::contains)
                .orElseThrow(() -> new IOException("Langage texte non pris en charge : " + file));
        return new AnalysisResult(file, language.id(), List.of(), List.of());
    }
}
