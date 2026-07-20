package com.nexus.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class NexusReadinessCheck implements HealthCheck {

    @Inject
    NexusApiApplicationService service;

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("nexus-context-engine")
                .up()
                .withData("nexusHome", service.paths().home().toString())
                .withData("storage", "sqlite")
                .withData("search", "lucene")
                .build();
    }
}
