package com.nexus.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Allowlist de racines projet administrables via REST.
 *
 * <p>Une configuration absente conserve le mode local historique. En revanche,
 * {@link NexusRestExposureGuard} exige une allowlist non vide dès que le serveur
 * écoute hors loopback. Les chemins sont canonicalisés avant comparaison.</p>
 */
final class NexusRestProjectRootPolicy {

    static final String ROOTS_ENVIRONMENT_VARIABLE = "NEXUS_REST_ALLOWED_PROJECT_ROOTS";
    static final String ROOTS_PROPERTY = "nexus.rest.allowed-project-roots";

    private NexusRestProjectRootPolicy() {
    }

    static List<Path> configuredRoots() {
        String configured = System.getProperty(ROOTS_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(ROOTS_ENVIRONMENT_VARIABLE);
        }
        if (configured == null || configured.isBlank()) {
            return List.of();
        }

        List<Path> roots = new ArrayList<>();
        for (String value : configured.replace('\r', ';').replace('\n', ';').split(";")) {
            if (value.isBlank()) {
                continue;
            }
            Path root = Path.of(value.trim()).toAbsolutePath().normalize();
            try {
                roots.add(root.toRealPath());
            } catch (IOException exception) {
                throw new IllegalStateException("Racine REST autorisée introuvable : " + root, exception);
            }
        }
        return List.copyOf(roots);
    }

    static Path requireAllowed(Path requestedRoot) throws IOException {
        Path canonical = requestedRoot.toAbsolutePath().normalize().toRealPath();
        List<Path> configuredRoots = configuredRoots();
        if (configuredRoots.isEmpty()) {
            return canonical;
        }
        Optional<Path> match = configuredRoots.stream()
                .filter(canonical::startsWith)
                .findFirst();
        if (match.isEmpty()) {
            throw new IllegalArgumentException(
                    "Le projet REST " + canonical + " est hors des racines autorisées " + configuredRoots);
        }
        return canonical;
    }
}
