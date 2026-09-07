package com.nexus.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusRestManagementExposureGuardTest {

    @Test
    void rejectsManagementListenerOutsideLoopbackBeforeValidatingApplicationExposure() {
        NexusRestExposureGuard guard = new NexusRestExposureGuard();
        guard.host = "127.0.0.1";
        guard.managementHost = "0.0.0.0";

        IllegalStateException error = assertThrows(IllegalStateException.class, guard::validateExposure);

        assertTrue(error.getMessage().contains("quarkus.management.host"));
        assertTrue(error.getMessage().contains("loopback"));
    }
}
