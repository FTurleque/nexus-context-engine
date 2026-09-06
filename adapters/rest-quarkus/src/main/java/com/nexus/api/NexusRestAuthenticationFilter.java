package com.nexus.api;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Authentification Bearer légère de NEXUS.
 *
 * <p>Le garde d'exposition valide au démarrage qu'un token robuste est présent
 * sur loopback et hors loopback, sauf opt-out local explicite via
 * {@code NEXUS_REST_TRUST_LOCAL=true}. Dès qu'un token est configuré, toutes
 * les ressources REST JAX-RS l'exigent.</p>
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class NexusRestAuthenticationFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        Optional<String> configuredToken = NexusRestSecurity.configuredToken();
        if (configuredToken.isEmpty()) {
            return;
        }

        String authorization = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (NexusRestSecurity.tokenMatches(configuredToken.get(), authorization)) {
            return;
        }

        requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(Map.of(
                        "error", "UNAUTHORIZED",
                        "message", "Bearer token NEXUS manquant ou invalide"))
                .build());
    }
}
