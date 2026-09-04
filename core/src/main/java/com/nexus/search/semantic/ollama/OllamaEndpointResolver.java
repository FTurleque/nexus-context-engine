package com.nexus.search.semantic.ollama;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/**
 * Résout et valide l'endpoint Ollama en fonction du runtime d'exécution.
 *
 * <p>En natif Windows, {@code http://127.0.0.1:11434} désigne bien l'hôte qui exécute Ollama. Dans
 * un conteneur Docker en revanche, {@code 127.0.0.1} désigne le conteneur NEXUS lui-même : pour
 * joindre un Ollama qui tourne sur l'hôte Windows via Docker Desktop, il faut utiliser
 * {@code host.docker.internal}. Cette bascule est appliquée uniquement aux adresses de bouclage.</p>
 *
 * <p>Par sécurité, HTTP est accepté sans opt-in uniquement pour une adresse de bouclage configurée.
 * Tout endpoint distant doit utiliser HTTPS, sauf opt-in administratif explicite.</p>
 */
public final class OllamaEndpointResolver {

    /** Hôte spécial Docker Desktop qui pointe vers la machine hôte depuis un conteneur. */
    public static final String DOCKER_HOST_GATEWAY = "host.docker.internal";

    private OllamaEndpointResolver() {
    }

    /** Résout l'URI en refusant par défaut tout HTTP distant. */
    public static URI resolveForRuntime(URI baseUri, boolean dockerRuntime) {
        return resolveForRuntime(baseUri, dockerRuntime, false);
    }

    /**
     * Résout l'URI de base Ollama pour le runtime cible.
     *
     * @param baseUri                     URI configurée par l'utilisateur
     * @param dockerRuntime               {@code true} si NEXUS s'exécute dans un conteneur Docker
     * @param allowInsecureRemoteEndpoint autorise explicitement HTTP vers un hôte distant
     * @return l'URI validée et adaptée au runtime
     */
    public static URI resolveForRuntime(
            URI baseUri,
            boolean dockerRuntime,
            boolean allowInsecureRemoteEndpoint) {
        Objects.requireNonNull(baseUri, "baseUri");
        validateEndpoint(baseUri, allowInsecureRemoteEndpoint);

        if (!dockerRuntime) {
            return baseUri;
        }
        String host = baseUri.getHost();
        if (!isLoopbackHost(host)) {
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
            throw new IllegalArgumentException("Impossible d'adapter l'endpoint Ollama au runtime Docker", exception);
        }
    }

    static void validateEndpoint(URI baseUri, boolean allowInsecureRemoteEndpoint) {
        String scheme = baseUri.getScheme();
        String host = baseUri.getHost();
        if (scheme == null || host == null || host.isBlank()) {
            throw new IllegalArgumentException("NEXUS_OLLAMA_BASE_URL doit être une URI HTTP(S) absolue avec hôte");
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
            throw new IllegalArgumentException("NEXUS_OLLAMA_BASE_URL accepte uniquement http ou https");
        }
        if (baseUri.getUserInfo() != null) {
            throw new IllegalArgumentException("NEXUS_OLLAMA_BASE_URL ne doit pas contenir de credentials dans l'URI");
        }
        if ("http".equals(normalizedScheme)
                && !isLoopbackHost(host)
                && !allowInsecureRemoteEndpoint) {
            throw new IllegalArgumentException(
                    "Un endpoint Ollama distant doit utiliser HTTPS. "
                            + "Pour accepter explicitement HTTP, activez NEXUS_ALLOW_INSECURE_REMOTE_OLLAMA=true");
        }
    }

    /**
     * Indique si un hôte est une adresse de bouclage (IPv4 127.0.0.0/8, {@code localhost}, ou IPv6
     * {@code ::1}). {@link URI#getHost()} conserve les crochets pour un IPv6 littéral, gérés ici.
     */
    static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
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
