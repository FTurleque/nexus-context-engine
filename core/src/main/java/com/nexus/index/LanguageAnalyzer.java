package com.nexus.index;

import java.io.IOException;
import java.nio.file.Path;

public interface LanguageAnalyzer {

    boolean supports(Path file);

    AnalysisResult analyze(Path projectRoot, Path file) throws IOException;

    /**
     * Analyse le contenu déjà lu et validé par le pipeline d'indexation.
     *
     * <p>L'implémentation par défaut conserve la compatibilité des analyseurs
     * externes. Les analyseurs embarqués qui consomment le contenu du fichier
     * doivent surcharger cette méthode afin que hash, recherche et analyse
     * structurelle portent sur exactement le même snapshot d'octets.</p>
     */
    default AnalysisResult analyze(Path projectRoot, Path file, String content) throws IOException {
        return analyze(projectRoot, file);
    }
}
