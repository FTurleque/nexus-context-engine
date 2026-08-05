package com.nexus.api;

import com.nexus.application.NexusApplication;
import com.nexus.project.IndexStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class NexusReadinessCheck implements HealthCheck {

    @Inject
    NexusApiApplicationService service;

    @Override
    public HealthCheckResponse call() {
        try {
            NexusApplication.ReadinessSnapshot snapshot = service.readiness();
            HealthCheckResponseBuilder response = HealthCheckResponse.named("nexus-context-engine")
                    .status(snapshot.operational())
                    .withData("storage", "sqlite")
                    .withData("search", "lucene")
                    .withData("registeredProjects", snapshot.registeredProjects())
                    .withData("allProjectsReady", snapshot.allProjectsReady())
                    .withData("degraded", snapshot.degraded())
                    .withData("semanticSearchEnabled", snapshot.semanticSearchEnabled());
            for (IndexStatus status : IndexStatus.values()) {
                response.withData(
                        "projects." + status.name().toLowerCase(),
                        snapshot.projectsByStatus().getOrDefault(status, 0));
            }
            return response.build();
        } catch (RuntimeException exception) {
            return HealthCheckResponse.named("nexus-context-engine")
                    .down()
                    .withData("error", exception.getClass().getSimpleName())
                    .build();
        }
    }
}
