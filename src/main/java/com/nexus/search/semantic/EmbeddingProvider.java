package com.nexus.search.semantic;

import java.io.IOException;

/**
 * Port d'embeddings optionnel pour la recherche sémantique.
 *
 * <p>NEXUS ne suppose ni fournisseur, ni transport, ni runtime. Une implémentation
 * locale ou externe doit être configurée explicitement par la composition
 * applicative qui souhaite activer la recherche sémantique.</p>
 */
public interface EmbeddingProvider {

    /**
     * Identité stable et observable du modèle utilisé, par exemple nom + version.
     */
    String modelId();

    /**
     * Dimension exacte des vecteurs produits par ce provider.
     */
    int dimensions();

    /**
     * Produit le vecteur correspondant au texte fourni.
     */
    float[] embed(String text) throws IOException;
}
