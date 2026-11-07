package com.nexus.project;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FederationDeadlinePolicyTest {

    @Test
    void defaultsToSixtySecondsWithoutEnvironmentValue() {
        assertEquals(Duration.ofSeconds(60), FederationDeadlinePolicy.fromEnvironment(name -> null).duration());
        assertEquals(Duration.ofSeconds(60), FederationDeadlinePolicy.fromEnvironment(name -> "  ").duration());
        assertEquals(Duration.ofSeconds(60), FederationDeadlinePolicy.DEFAULT_DURATION);
    }

    @Test
    void readsTheEnvironmentValueWithinTheBounds() {
        Map<String, String> environment = Map.of(FederationDeadlinePolicy.ENVIRONMENT_VARIABLE, " 90 ");

        assertEquals(Duration.ofSeconds(90), FederationDeadlinePolicy.fromEnvironment(environment::get).duration());
    }

    @Test
    void acceptsTheExactBoundsAndRejectsOneSecondOutside() {
        assertEquals(Duration.ofSeconds(1), policy("1").duration());
        assertEquals(Duration.ofSeconds(600), policy("600").duration());
        assertThrows(IllegalArgumentException.class, () -> policy("0"));
        assertThrows(IllegalArgumentException.class, () -> policy("601"));
        assertThrows(IllegalArgumentException.class, () -> policy("-5"));
    }

    @Test
    void rejectsANonIntegerValueWithAFrenchMessage() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> policy("soixante"));

        assertTrue(failure.getMessage().contains("doit être un entier en secondes"), failure.getMessage());
    }

    @Test
    void deadlineFollowsTheInjectedClock() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        FederationDeadline deadline = new FederationDeadlinePolicy(Duration.ofSeconds(10), now::get).start();

        assertFalse(deadline.expired());
        assertEquals(Duration.ofSeconds(10), deadline.remaining());

        now.set(Instant.parse("2026-01-01T00:00:09.999Z"));
        assertFalse(deadline.expired());

        now.set(Instant.parse("2026-01-01T00:00:10Z"));
        assertTrue(deadline.expired());
        assertEquals(Duration.ZERO, deadline.remaining());

        now.set(Instant.parse("2026-01-01T00:01:00Z"));
        assertEquals(Duration.ZERO, deadline.remaining());
    }

    private static FederationDeadlinePolicy policy(String value) {
        Map<String, String> environment = Map.of(FederationDeadlinePolicy.ENVIRONMENT_VARIABLE, value);
        return FederationDeadlinePolicy.fromEnvironment(environment::get);
    }
}
