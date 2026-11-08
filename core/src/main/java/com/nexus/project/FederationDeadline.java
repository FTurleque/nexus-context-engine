package com.nexus.project;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;

/**
 * Échéance globale d'une requête fédérée (BE-0081).
 *
 * <p>L'échéance est vérifiée entre deux projets : un projet commencé n'est jamais
 * interrompu. L'horloge est injectée afin que les tests simulent le temps sans
 * dormir.</p>
 */
public final class FederationDeadline {

    private final InstantSource clock;
    private final Instant deadline;

    FederationDeadline(InstantSource clock, Duration duration) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deadline = clock.instant().plus(Objects.requireNonNull(duration, "duration"));
    }

    /** Vrai dès que l'instant courant atteint ou dépasse l'échéance. */
    public boolean expired() {
        return !clock.instant().isBefore(deadline);
    }

    /** Temps restant avant l'échéance, jamais négatif. */
    public Duration remaining() {
        Duration remaining = Duration.between(clock.instant(), deadline);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }
}
