package com.nexus.search.semantic;

import java.io.IOException;

/**
 * Signale une indisponibilité transitoire du transport/provider d'embeddings.
 *
 * <p>Cette exception est distincte des erreurs de protocole ou de contrat afin
 * que la recherche puisse dégrader uniquement un provider indisponible sans
 * masquer une réponse invalide, une dimension incohérente ou un bug de modèle.</p>
 */
public final class EmbeddingProviderUnavailableException extends IOException {

    public EmbeddingProviderUnavailableException(String message) {
        super(message);
    }

    public EmbeddingProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
