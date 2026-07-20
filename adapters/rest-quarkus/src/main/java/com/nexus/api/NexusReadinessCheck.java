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
        try {
            int registeredProjects = service.listProjects().size();
            return HealthCheckResponse.named("nexus-context-engine")
                    .up()
                    .withData("storage", "sqlite")
                    .withData("search", "lucene")
                    .withData("registeredProjects", registeredProjects)
                    .build();
        } catch (RuntimeException exception) {
            return HealthCheckResponse.named("nexus-context-engine")
                    .down()
                    .withData("error", exception.getClass().getSimpleName())
                    .build();
        }
    }
}
