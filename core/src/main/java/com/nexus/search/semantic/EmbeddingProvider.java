package com.nexus.search.semantic;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Port d'embeddings optionnel pour la recherche sémantique.
 *
 * <p>NEXUS ne suppose ni fournisseur, ni transport, ni runtime. Une implémentation
 * locale ou externe doit être configurée explicitement par la composition
 * applicative qui souhaite activer la recherche sémantique.</p>
 */
public interface EmbeddingProvider {

    /**
     * Identité stable du provider/transport. Les implémentations peuvent la
     * surcharger lorsqu'elles disposent d'un identifiant métier plus explicite.
     */
    default String providerId() {
        return getClass().getName();
    }

    /** Identité stable et observable du modèle utilisé. */
    String modelId();

    /** Dimension exacte des vecteurs produits par ce provider. */
    int dimensions();

    /** Produit le vecteur correspondant au texte fourni. */
    float[] embed(String text) throws IOException;

    /**
     * Produit plusieurs vecteurs en un appel logique. L'implémentation par
     * défaut reste séquentielle pour préserver tous les providers existants ;
     * les transports capables de batcher peuvent surcharger cette méthode.
     */
    default List<float[]> embedAll(List<String> texts) throws IOException {
        Objects.requireNonNull(texts, "texts");
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return List.copyOf(vectors);
    }
}
