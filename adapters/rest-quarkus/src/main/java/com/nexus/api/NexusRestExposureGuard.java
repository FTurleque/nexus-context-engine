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
            validateHardenedLoopback();
            return;
        }

        requireStrongToken("NEXUS REST refuse une écoute hors loopback sans authentification. Configurez ");
        requireProjectRoots("Une écoute REST hors loopback exige ");

        String exposureMode = NexusRestSecurity.configuredExposureMode()
                .orElseThrow(() -> new IllegalStateException(
                        "Une écoute REST hors loopback exige "
                                + NexusRestSecurity.EXPOSURE_MODE_ENVIRONMENT_VARIABLE
                                + "=reverse-proxy-https|direct-https, ou loopback-forward dans le runtime Docker "
                                + "avec un forward hôte explicitement déclaré comme loopback."));

        if (NexusRestSecurity.isLoopbackForwardMode(exposureMode)) {
            validateDockerLoopbackForward();
            return;
        }
        if (!NexusRestSecurity.isSecureNonLoopbackExposureMode(exposureMode)) {
            throw new IllegalStateException(
                    "Une écoute REST hors loopback refuse le mode " + exposureMode
                            + ". Utilisez reverse-proxy-https ou direct-https. "
                            + "loopback-forward est réservé à un forward Docker explicitement déclaré sur loopback.");
        }

        NexusRestTransportPolicy.validateSecureNonLoopbackExposure(exposureMode);
    }

    private static void validateHardenedLoopback() {
        if (!NexusRestSecurity.isLocalHardeningRequired()) {
            return;
        }
        requireStrongToken(
                NexusRestSecurity.LOCAL_HARDENING_ENVIRONMENT_VARIABLE
                        + "=true exige une authentification locale. Configurez ");
        requireProjectRoots(
                NexusRestSecurity.LOCAL_HARDENING_ENVIRONMENT_VARIABLE
                        + "=true exige une allowlist de projets via ");
    }

    private static String requireStrongToken(String prefix) {
        String token = NexusRestSecurity.configuredToken()
                .orElseThrow(() -> new IllegalStateException(
                        prefix + NexusRestSecurity.TOKEN_ENVIRONMENT_VARIABLE + "."));
        if (!NexusRestSecurity.isStrongRemoteToken(token)) {
            throw new IllegalStateException(
                    NexusRestSecurity.TOKEN_ENVIRONMENT_VARIABLE + " doit contenir au moins "
                            + NexusRestSecurity.MIN_REMOTE_TOKEN_BYTES
                            + " octets et présenter une entropie estimée d'au moins "
                            + (int) NexusRestSecurity.MIN_REMOTE_TOKEN_ESTIMATED_ENTROPY_BITS
                            + " bits");
        }
        return token;
    }

    private static List<Path> requireProjectRoots(String prefix) {
        List<Path> roots = NexusRestProjectRootPolicy.configuredRoots();
        if (roots.isEmpty()) {
            throw new IllegalStateException(
                    prefix + NexusRestProjectRootPolicy.ROOTS_ENVIRONMENT_VARIABLE
                            + " afin de borner les répertoires administrables");
        }
        return roots;
    }

    private static void validateDockerLoopbackForward() {
        if (NexusRestSecurity.configuredRuntime().filter("docker"::equals).isEmpty()) {
            throw new IllegalStateException(
                    "NEXUS_REST_EXPOSURE_MODE=loopback-forward exige NEXUS_RUNTIME=docker");
        }

        String declaredForward = NexusRestSecurity.configuredDockerHostForwardAddress()
                .orElseThrow(() -> new IllegalStateException(
                        "NEXUS_REST_EXPOSURE_MODE=loopback-forward exige une déclaration explicite "
                                + NexusRestSecurity.DOCKER_HOST_FORWARD_ADDRESS_ENVIRONMENT_VARIABLE
                                + ". NEXUS ne peut pas introspecter l'adresse de publication choisie par le daemon Docker."));
        if (!NexusRestSecurity.isLoopbackHost(declaredForward)) {
            throw new IllegalStateException(
                    NexusRestSecurity.DOCKER_HOST_FORWARD_ADDRESS_ENVIRONMENT_VARIABLE
                            + " doit être une adresse loopback lorsque NEXUS_REST_EXPOSURE_MODE=loopback-forward; "
                            + "une publication distante doit utiliser reverse-proxy-https ou direct-https");
        }
    }
}
