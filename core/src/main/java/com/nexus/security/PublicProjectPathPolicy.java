package com.nexus.security;

import com.nexus.paths.RepositoryPath;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Fail-closed projection of repository paths at public output boundaries.
 *
 * <p>This policy performs lexical containment only. Filesystem access remains the
 * responsibility of {@link ProjectPathGuard}; this class exists specifically to
 * prevent REST, MCP and CLI serializers from exposing an absolute path when an
 * upstream invariant is broken.</p>
 */
public final class PublicProjectPathPolicy {

    private PublicProjectPathPolicy() {
    }

    /**
     * Returns a normalized repository-relative path using forward slashes.
     * Relative inputs are accepted only when they remain inside the project after
     * normalization. Absolute inputs must be lexically contained by the project root.
     */
    public static String expose(Path projectRoot, Path candidatePath) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(candidatePath, "candidatePath");

        Path root = projectRoot.toAbsolutePath().normalize();
        Path normalized = candidatePath.normalize();
        Path relative;
        if (normalized.isAbsolute()) {
            Path absolute = normalized.toAbsolutePath().normalize();
            if (!absolute.startsWith(root)) {
                throw outsideProject();
            }
            relative = root.relativize(absolute);
        } else {
            Path resolved = root.resolve(normalized).normalize();
            if (!resolved.startsWith(root)) {
                throw outsideProject();
            }
            relative = root.relativize(resolved);
        }
        return RepositoryPath.encode(relative);
    }

    private static IllegalStateException outsideProject() {
        return new IllegalStateException("Result path is outside the project root");
    }
}
