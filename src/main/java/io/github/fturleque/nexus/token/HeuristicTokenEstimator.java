package io.github.fturleque.nexus.token;

/**
 * Estimateur local et déterministe utilisé par défaut par NEXUS.
 *
 * <p>L'heuristique utilise environ 3,5 points de code Unicode par token. Elle
 * ne prétend pas reproduire le tokenizer d'un fournisseur ; elle fournit une
 * unité de budget locale, stable et remplaçable via {@link TokenEstimator}.</p>
 */
public final class HeuristicTokenEstimator implements TokenEstimator {

    private static final double CODE_POINTS_PER_TOKEN = 3.5d;

    @Override
    public int estimate(CharSequence text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        String value = text.toString();
        int codePoints = value.codePointCount(0, value.length());
        return Math.max(1, (int) Math.ceil(codePoints / CODE_POINTS_PER_TOKEN));
    }

    @Override
    public String toString() {
        return "heuristic-unicode-3.5-chars-per-token";
    }
}
