package com.nexus.security;

/**
 * Marque un record de métadonnées qui ne porte que des compteurs numériques validés.
 *
 * <p>{@link PublicDiagnosticPolicy} laisse passer ces records tels quels aux frontières publiques,
 * ce qui préserve le contrat des snapshots de budgets sans faire dépendre {@code security} des
 * packages qui les déclarent. Un record ne l'implémente que si aucune de ses composantes n'est un
 * texte, un chemin ou une collection.</p>
 */
public interface PublicCounterSnapshot {
}
