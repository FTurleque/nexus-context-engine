package com.nexus.api;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.util.List;

/** Empêche une exposition réseau accidentelle de l'API de contexte. */
@Startup
@ApplicationScoped
public class NexusRestExposureGuard {

    @ConfigProperty(name = "quarkus.http.host", defaultValue = "127.0.0.1")
    String host;

    @PostConstruct
    void validateExposure() {
        if (NexusRestSecurity.isLoopbackHost(host)) {
            return;
        }

        String token = NexusRestSecurity.configuredToken()
                .orElseThrow(() -> new IllegalStateException(
                        "NEXUS REST refuse une écoute hors loopback sans authentification. Configurez "
                                + NexusRestSecurity.TOKEN_ENVIRONMENT_VARIABLE + "."));
        if (!NexusRestSecurity.isStrongRemoteToken(token)) {
            throw new IllegalStateException(
                    NexusRestSecurity.TOKEN_ENVIRONMENT_VARIABLE + " doit contenir au moins "
                            + NexusRestSecurity.MIN_REMOTE_TOKEN_BYTES
                            + " octets et présenter une entropie estimée d'au moins "
                            + (int) NexusRestSecurity.MIN_REMOTE_TOKEN_ESTIMATED_ENTROPY_BITS
                            + " bits pour une écoute hors loopback");
        }

        List<Path> roots = NexusRestProjectRootPolicy.configuredRoots();
        if (roots.isEmpty()) {
            throw new IllegalStateException(
                    "Une écoute REST hors loopback exige "
                            + NexusRestProjectRootPolicy.ROOTS_ENVIRONMENT_VARIABLE
                            + " afin de borner les répertoires administrables");
        }

        String exposureMode = NexusRestSecurity.configuredExposureMode()
                .orElseThrow(() -> new IllegalStateException(
                        "Une écoute REST hors loopback exige "
                                + NexusRestSecurity.EXPOSURE_MODE_ENVIRONMENT_VARIABLE
                                + "=reverse-proxy-https|direct-https"));
        if (!NexusRestSecurity.isSecureNonLoopbackExposureMode(exposureMode)) {
            throw new IllegalStateException(
                    "Une écoute REST hors loopback refuse le mode " + exposureMode
                            + ". Utilisez reverse-proxy-https ou direct-https ; "
                            + "loopback-forward exige une écoute Quarkus réellement loopback.");
        }
    }
}
