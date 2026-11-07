package com.nexus.index.scip;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScipIndexLimitsTest {

    @Test
    void keepsDefaultsWhenNothingIsConfigured() {
        assertEquals(ScipIndexLimits.defaults(), ScipIndexLimits.from(Map.of()));
    }

    @Test
    void acceptsValuesAtHardResourceBoundaries() {
        ScipIndexLimits limits = ScipIndexLimits.from(Map.of(
                ScipIndexLimits.MAX_INDEX_BYTES_ENV, Long.toString(ScipIndexLimits.MAX_CONFIGURABLE_INDEX_BYTES),
                ScipIndexLimits.MAX_MESSAGE_BYTES_ENV, Integer.toString(ScipIndexLimits.MAX_CONFIGURABLE_MESSAGE_BYTES)));

        assertEquals(ScipIndexLimits.MAX_CONFIGURABLE_INDEX_BYTES, limits.maxIndexBytes());
        assertEquals(ScipIndexLimits.MAX_CONFIGURABLE_MESSAGE_BYTES, limits.maxMessageBytes());
    }

    @Test
    void rejectsValuesAboveHardResourceBoundaries() {
        IllegalArgumentException index = assertThrows(IllegalArgumentException.class, () -> ScipIndexLimits.from(Map.of(
                ScipIndexLimits.MAX_INDEX_BYTES_ENV, Long.toString(ScipIndexLimits.MAX_CONFIGURABLE_INDEX_BYTES + 1L))));
        assertEquals(ScipIndexLimits.MAX_INDEX_BYTES_ENV + " doit être compris entre 1 et "
                + ScipIndexLimits.MAX_CONFIGURABLE_INDEX_BYTES + " octets", index.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ScipIndexLimits.from(Map.of(
                ScipIndexLimits.MAX_MESSAGE_BYTES_ENV, Integer.toString(ScipIndexLimits.MAX_CONFIGURABLE_MESSAGE_BYTES + 1))));
        assertThrows(IllegalArgumentException.class, () -> ScipIndexLimits.from(Map.of(
                ScipIndexLimits.MAX_INDEX_BYTES_ENV, "not-a-number")));
    }
}
