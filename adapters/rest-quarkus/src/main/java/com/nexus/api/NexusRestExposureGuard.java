package com.nexus.api;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Empêche une exposition réseau accidentelle de l'API de contexte sans secret.
 */
@Startup
@ApplicationScoped
public class NexusRestExposureGuard {

    @ConfigProperty(name = "quarkus.http.host", defaultValue = "127.0.0.1")
    String host;

    @PostConstruct
    void validateExposure() {
        if (!NexusRestSecurity.isLoopbackHost(host) && NexusRestSecurity.configuredToken().isEmpty()) {
            throw new IllegalStateException(
                    "NEXUS REST refuse une écoute hors loopback sans authentification. "
                            + "Configurez " + NexusRestSecurity.TOKEN_ENVIRONMENT_VARIABLE
                            + " (ou -D" + NexusRestSecurity.TOKEN_PROPERTY + ") avant d'exposer quarkus.http.host=" + host);
        }
    }
}
