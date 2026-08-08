package com.nexus.search.semantic.ollama;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/**
 * Résout l'endpoint Ollama en fonction du runtime d'exécution.
 *
 * <p>En natif Windows, {@code http://127.0.0.1:11434} désigne bien l'hôte qui exécute Ollama. Dans
 * un conteneur Docker en revanche, {@code 127.0.0.1} désigne le conteneur NEXUS lui-même : pour
 * joindre un Ollama qui tourne sur l'hôte Windows via Docker Desktop, il faut utiliser
 * {@code host.docker.internal}. Ce résolveur applique cette bascule <strong>uniquement</strong>
 * pour les adresses de bouclage, afin de ne jamais casser :</p>
 * <ul>
 *   <li>un hôte distant explicite ;</li>
 *   <li>un DNS personnalisé ;</li>
 *   <li>un Ollama dans un autre conteneur ;</li>
 *   <li>un Ollama sur une autre machine ;</li>
 *   <li>une adresse IPv6 non-bouclage.</li>
 * </ul>
 * Ces cas, accessibles à l'identique depuis les deux runtimes, sont renvoyés inchangés.
 */
public final class OllamaEndpointResolver {

    /** Hôte spécial Docker Desktop qui pointe vers la machine hôte depuis un conteneur. */
    public static final String DOCKER_HOST_GATEWAY = "host.docker.internal";

    private OllamaEndpointResolver() {
    }

    /**
     * Résout l'URI de base Ollama pour le runtime cible.
     *
     * @param baseUri       URI configurée par l'utilisateur
     * @param dockerRuntime {@code true} si NEXUS s'exécute dans un conteneur Docker
     * @return l'URI adaptée au runtime ; identique à l'entrée hors cas bouclage en Docker
     */
    public static URI resolveForRuntime(URI baseUri, boolean dockerRuntime) {
        Objects.requireNonNull(baseUri, "baseUri");
        if (!dockerRuntime) {
            return baseUri;
        }
        String host = baseUri.getHost();
        if (host == null || !isLoopbackHost(host)) {
            return baseUri;
        }
        try {
            return new URI(
                    baseUri.getScheme(),
                    baseUri.getUserInfo(),
                    DOCKER_HOST_GATEWAY,
                    baseUri.getPort(),
                    baseUri.getPath(),
                    baseUri.getQuery(),
                    baseUri.getFragment());
        } catch (URISyntaxException exception) {
            // Reconstruire avec un hôte DNS trivial ne peut échouer que sur une URI déjà invalide ;
            // dans le doute on préserve l'entrée plutôt que de propager une erreur ici.
            return baseUri;
        }
    }

    /**
     * Indique si un hôte est une adresse de bouclage (IPv4 127.0.0.0/8, {@code localhost}, ou IPv6
     * {@code ::1}). {@link URI#getHost()} conserve les crochets pour un IPv6 littéral, gérés ici.
     */
    static boolean isLoopbackHost(String host) {
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.equals("localhost") || normalized.equals("::1")) {
            return true;
        }
        return normalized.startsWith("127.") && isIpv4(normalized);
    }

    private static boolean isIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int index = 0; index < part.length(); index++) {
                if (!Character.isDigit(part.charAt(index))) {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }
}
