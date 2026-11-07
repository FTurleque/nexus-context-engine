package com.nexus.project;

import java.time.Duration;
import java.time.InstantSource;
import java.util.Map;
import java.util.Objects;

/**
 * Politique d'échéance globale d'une requête fédérée (BE-0064) : durée et horloge.
 *
 * <p>La durée par défaut est {@link #DEFAULT_DURATION}, modifiable par
 * {@value #ENVIRONMENT_VARIABLE} dans l'intervalle {@link #MIN_DURATION} à
 * {@link #MAX_DURATION}. La composition root résout cette valeur par {@link #from(Map)} et
 * transmet la politique aux services fédérés ; la classe ne lit pas l'environnement
 * (ARCH-0066).</p>
 */
public final class FederationDeadlinePolicy {

    public static final String ENVIRONMENT_VARIABLE = "NEXUS_FEDERATION_DEADLINE_SECONDS";
    public static final Duration DEFAULT_DURATION = Duration.ofSeconds(60);
    public static final Duration MIN_DURATION = Duration.ofSeconds(1);
    public static final Duration MAX_DURATION = Duration.ofSeconds(600);

    private final Duration duration;
    private final InstantSource clock;

    public FederationDeadlinePolicy(Duration duration, InstantSource clock) {
        this.duration = Objects.requireNonNull(duration, "duration");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (duration.compareTo(MIN_DURATION) < 0 || duration.compareTo(MAX_DURATION) > 0) {
            throw new IllegalArgumentException(ENVIRONMENT_VARIABLE + " doit être compris entre "
                    + MIN_DURATION.toSeconds() + " et " + MAX_DURATION.toSeconds() + " secondes");
        }
    }

    /** Politique par défaut ({@link #DEFAULT_DURATION}) sur l'horloge système. */
    public static FederationDeadlinePolicy defaults() {
        return new FederationDeadlinePolicy(DEFAULT_DURATION, InstantSource.system());
    }

    /**
     * Politique sur l'horloge système, durée lue dans {@code environment} sous
     * {@value #ENVIRONMENT_VARIABLE} ; valeur absente ou vide : {@link #DEFAULT_DURATION}.
     */
    public static FederationDeadlinePolicy from(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String configured = environment.get(ENVIRONMENT_VARIABLE);
        if (configured == null || configured.isBlank()) {
            return defaults();
        }
        try {
            return new FederationDeadlinePolicy(
                    Duration.ofSeconds(Long.parseLong(configured.trim())), InstantSource.system());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    ENVIRONMENT_VARIABLE + " doit être un entier en secondes", exception);
        }
    }

    public Duration duration() {
        return duration;
    }

    /** Démarre l'échéance d'une requête fédérée à l'instant courant de l'horloge. */
    public FederationDeadline start() {
        return new FederationDeadline(clock, duration);
    }
}
