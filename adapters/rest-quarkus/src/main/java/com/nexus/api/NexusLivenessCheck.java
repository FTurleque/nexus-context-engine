package com.nexus.api;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Liveness volontairement minimale : si Quarkus peut invoquer ce check, le
 * processus NEXUS est vivant. La capacité du stockage et l'état des projets
 * relèvent de la readiness et des gates projet séparés.
 */
@Liveness
@ApplicationScoped
public class NexusLivenessCheck implements HealthCheck {

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.up("nexus-context-engine-liveness");
    }
}
