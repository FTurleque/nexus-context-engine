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
 * Authentification Bearer légère adaptée au mode local-first de NEXUS.
 *
 * <p>Sur loopback sans token configuré, le comportement historique reste
 * inchangé. Dès qu'un token est configuré — et il est obligatoire pour une
 * écoute non-loopback — toutes les ressources REST JAX-RS l'exigent.</p>
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
