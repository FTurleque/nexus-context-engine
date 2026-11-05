package com.nexus.security;

/**
 * Une valeur sensible dépasse la longueur que la redaction sait borner.
 *
 * <p>La redaction refuse alors tout le contenu, sans en publier de suffixe. Les appelants qui
 * traitent plusieurs fichiers ou fragments excluent le seul contenu concerné et produisent un
 * diagnostic public qui ne cite ni la valeur ni le contenu.</p>
 */
public final class OversizedSecretException extends IllegalArgumentException {

    /** Diagnostic public commun aux exclusions, sans valeur ni contenu. */
    public static final String PUBLIC_REASON = "valeur sensible dépassant la limite de redaction";

    /** Exclusion publique d'un fichier ; un chemin absolu ou hors projet n'est jamais cité. */
    public static String exclusion(java.nio.file.Path path) {
        String label;
        try {
            label = path.isAbsolute() ? "[INTERNAL_PATH]" : com.nexus.paths.RepositoryPath.encode(path);
        } catch (IllegalArgumentException invalid) {
            label = "[INTERNAL_PATH]";
        }
        return label + " exclu : " + PUBLIC_REASON;
    }

    OversizedSecretException() {
        super("Valeur sensible dépassant la limite de redaction");
    }
}
