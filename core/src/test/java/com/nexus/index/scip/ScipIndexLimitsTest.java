package com.nexus.index.scip;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScipIndexLimitsTest {

    @Test
    void acceptsValuesAtHardResourceBoundaries() {
        assertEquals(
                ScipIndexLimits.MAX_CONFIGURABLE_INDEX_BYTES,
                ScipIndexLimits.parseBoundedPositiveLong(
                        ScipIndexLimits.MAX_INDEX_BYTES_ENV,
                        Long.toString(ScipIndexLimits.MAX_CONFIGURABLE_INDEX_BYTES),
                        ScipIndexLimits.MAX_CONFIGURABLE_INDEX_BYTES));
        assertEquals(
                ScipIndexLimits.MAX_CONFIGURABLE_MESSAGE_BYTES,
                ScipIndexLimits.parseBoundedPositiveLong(
                        ScipIndexLimits.MAX_MESSAGE_BYTES_ENV,
                        Integer.toString(ScipIndexLimits.MAX_CONFIGURABLE_MESSAGE_BYTES),
                        ScipIndexLimits.MAX_CONFIGURABLE_MESSAGE_BYTES));
    }

    @Test
    void rejectsValuesAboveHardResourceBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> ScipIndexLimits.parseBoundedPositiveLong(
                ScipIndexLimits.MAX_INDEX_BYTES_ENV,
                Long.toString(ScipIndexLimits.MAX_CONFIGURABLE_INDEX_BYTES + 1L),
                ScipIndexLimits.MAX_CONFIGURABLE_INDEX_BYTES));
        assertThrows(IllegalArgumentException.class, () -> ScipIndexLimits.parseBoundedPositiveLong(
                ScipIndexLimits.MAX_MESSAGE_BYTES_ENV,
                Integer.toString(ScipIndexLimits.MAX_CONFIGURABLE_MESSAGE_BYTES + 1),
                ScipIndexLimits.MAX_CONFIGURABLE_MESSAGE_BYTES));
    }
}
