package com.nexus.context;

/**
 * Politique applicative unique pour le budget de tokens d'un {@link ContextRequest}.
 *
 * <p>La validation est centralisée ici afin que toutes les surfaces (CLI, REST, MCP, contexte
 * fédéré) héritent exactement de la même borne. Historiquement seule la borne basse
 * ({@code tokenBudget > 0}) était vérifiée, ce qui autorisait n'importe quel entier positif et
 * exposait le moteur à un épuisement mémoire (fragments retenus, buffers de rendu) via une valeur
 * démesurée fournie par un client.</p>
 *
 * <p>La borne haute {@link #MAX_CONTEXT_TOKEN_BUDGET} est un garde-fou conservateur : elle dépasse
 * largement le budget par défaut et les fenêtres de contexte usuelles des modèles ciblés, tout en
 * restant très en dessous des valeurs qui traduiraient une erreur d'appel ou un abus. Elle borne le
 * coût mémoire d'un bundle sans contraindre les usages légitimes. La valeur est volontairement
 * choisie de façon conservatrice plutôt que dérivée d'un modèle précis, faute de benchmark dédié à
 * la borne supérieure.</p>
 */
public final class ContextBudgetPolicy {

    /** Budget par défaut appliqué lorsqu'un client ne fournit pas de valeur explicite. */
    public static final int DEFAULT_CONTEXT_TOKEN_BUDGET = 2_000;

    /**
     * Borne haute inclusive du budget de tokens acceptée par le moteur. Au-delà, la demande est
     * rejetée comme non raisonnable (protection ressources), quel que soit le point d'entrée.
     */
    public static final int MAX_CONTEXT_TOKEN_BUDGET = 200_000;

    private ContextBudgetPolicy() {
    }

    /**
     * Valide un budget de tokens fourni par un client et le renvoie inchangé s'il est acceptable.
     *
     * @param tokenBudget budget demandé
     * @return {@code tokenBudget} lorsqu'il est dans les bornes autorisées
     * @throws IllegalArgumentException si le budget est nul, négatif, ou supérieur à
     *         {@link #MAX_CONTEXT_TOKEN_BUDGET}
     */
    public static int validate(int tokenBudget) {
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be greater than zero");
        }
        if (tokenBudget > MAX_CONTEXT_TOKEN_BUDGET) {
            throw new IllegalArgumentException(
                    "tokenBudget must not exceed " + MAX_CONTEXT_TOKEN_BUDGET
                            + " (requested " + tokenBudget + ")");
        }
        return tokenBudget;
    }
}
