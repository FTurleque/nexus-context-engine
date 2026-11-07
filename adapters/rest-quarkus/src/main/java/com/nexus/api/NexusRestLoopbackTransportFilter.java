package com.nexus.api;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Contrôle de transport du listener REST applicatif lorsqu'il écoute sur loopback.
 *
 * <p>L'activation est décidée une seule fois, à la construction, par
 * {@link NexusRestSecurity#requiresLoopbackTransportControl(String)} : le filtre reste actif
 * sauf si {@code quarkus.http.host} se résout avec certitude vers une adresse non loopback ; un
 * échec de résolution le garde actif. {@link NexusRestExposureGuard} refuse au démarrage un
 * listener loopback dont l'hôte n'est pas littéralement {@code 127.0.0.1}, {@code localhost}
 * ou {@code ::1}.</p>
 *
 * <p>Actif dès que {@code quarkus.http.host} désigne une adresse loopback, en mode
 * {@code NEXUS_REST_TRUST_LOCAL=true} comme en mode jeton loopback, ce filtre refuse avant
 * toute résolution de ressource une requête dont l'en-tête {@code Host} ne désigne pas
 * littéralement {@code 127.0.0.1}, {@code localhost} ou {@code [::1]} (avec ou sans port), ou
 * qui porte un en-tête {@code Origin} non loopback. Une requête sans {@code Origin} (client en
 * ligne de commande, assistant) reste acceptée. La comparaison est littérale et ne résout
 * aucun nom : un nom DNS qui se résoudrait vers loopback (rebinding) est refusé.</p>
 *
 * <p>Le refus est une réponse 403 sans détail : ni la valeur reçue ni la configuration
 * attendue ne figurent dans le corps. Hors loopback, ce filtre est inactif et seul le garde
 * de démarrage {@link NexusRestExposureGuard} (jeton, allowlist de racines, TLS) s'applique ;
 * ce contrôle n'assouplit aucune de ses exigences.</p>
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION - 100)
public class NexusRestLoopbackTransportFilter implements ContainerRequestFilter {

    static final String FORBIDDEN_ERROR = "FORBIDDEN";
    static final String FORBIDDEN_MESSAGE = "Requête refusée par le contrôle de transport local";

    private static final String ORIGIN_HEADER = "Origin";
    private static final Set<String> LOOPBACK_HOST_NAMES = Set.of("127.0.0.1", "localhost", "[::1]");
    private static final int MAX_PORT_DIGITS = 5;

    private final boolean active;

    @Inject
    public NexusRestLoopbackTransportFilter(
            @ConfigProperty(name = "quarkus.http.host", defaultValue = "127.0.0.1") String host) {
        this.active = NexusRestSecurity.requiresLoopbackTransportControl(host);
    }

    /** Vrai si le contrôle de transport s'applique au listener configuré. */
    boolean active() {
        return active;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!active) {
            return;
        }
        if (isAcceptedOnLoopbackListener(
                requestContext.getHeaderString(HttpHeaders.HOST),
                requestContext.getHeaderString(ORIGIN_HEADER))) {
            return;
        }
        requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(Map.of(
                        "error", FORBIDDEN_ERROR,
                        "message", FORBIDDEN_MESSAGE))
                .build());
    }

    /**
     * Décide si une requête reçue sur un listener loopback est acceptée.
     *
     * @param hostHeader valeur de l'en-tête {@code Host}, {@code null} si absent
     * @param originHeader valeur de l'en-tête {@code Origin}, {@code null} si absent
     */
    static boolean isAcceptedOnLoopbackListener(String hostHeader, String originHeader) {
        if (!isLoopbackAuthority(hostHeader)) {
            return false;
        }
        return originHeader == null || isLoopbackOrigin(originHeader);
    }

    /** Une origine loopback est {@code http://} ou {@code https://} suivi d'une autorité loopback, sans chemin. */
    static boolean isLoopbackOrigin(String origin) {
        String normalized = origin.trim().toLowerCase(Locale.ROOT);
        String authority;
        if (normalized.startsWith("http://")) {
            authority = normalized.substring("http://".length());
        } else if (normalized.startsWith("https://")) {
            authority = normalized.substring("https://".length());
        } else {
            return false;
        }
        return isLoopbackAuthority(authority);
    }

    /** Une autorité loopback est {@code 127.0.0.1}, {@code localhost} ou {@code [::1]}, suivie d'un port facultatif. */
    static boolean isLoopbackAuthority(String authority) {
        if (authority == null || authority.isBlank()) {
            return false;
        }
        String normalized = authority.trim().toLowerCase(Locale.ROOT);
        String hostName;
        String portSuffix;
        if (normalized.startsWith("[")) {
            int closing = normalized.indexOf(']');
            if (closing < 0) {
                return false;
            }
            hostName = normalized.substring(0, closing + 1);
            portSuffix = normalized.substring(closing + 1);
        } else {
            int colon = normalized.indexOf(':');
            hostName = colon < 0 ? normalized : normalized.substring(0, colon);
            portSuffix = colon < 0 ? "" : normalized.substring(colon);
        }
        return LOOPBACK_HOST_NAMES.contains(hostName) && isEmptyOrPort(portSuffix);
    }

    private static boolean isEmptyOrPort(String suffix) {
        if (suffix.isEmpty()) {
            return true;
        }
        if (suffix.charAt(0) != ':' || suffix.length() < 2 || suffix.length() > MAX_PORT_DIGITS + 1) {
            return false;
        }
        for (int index = 1; index < suffix.length(); index++) {
            char character = suffix.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }
}
