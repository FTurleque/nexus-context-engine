package com.nexus.token;

/**
 * Estime le coût en tokens d'un texte pour les budgets de contexte.
 *
 * <p>Une implémentation doit être déterministe et croissante avec le préfixe : si {@code a} est
 * un préfixe de {@code b}, alors {@code estimate(a) <= estimate(b)}. La troncature de fragment de
 * {@code BudgetedContextSelector} cherche le préfixe conservé par dichotomie et ne retient le plus
 * grand préfixe qui tient dans le budget qu'à cette condition.</p>
 */
public interface TokenEstimator {

    int estimate(CharSequence text);
}
