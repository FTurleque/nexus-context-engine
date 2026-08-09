package com.nexus.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that the Quarkus test HTTP server is configured to bind to a randomly
 * allocated port (quarkus.http.test-port=0) rather than the fixed default (8081).
 *
 * Without this guard, running the Maven build while a NEXUS Docker container is
 * already mapped to 127.0.0.1:8081 causes QuarkusBindException and fails the build.
 * The configuration lives in src/test/resources/application.properties.
 */
class NexusTestPortHermeticityTest {

    @Test
    void testResourcesConfigureDynamicHttpTestPortToPreventConflictsWithRunningNexusContainers()
            throws IOException {
        URL resource = NexusTestPortHermeticityTest.class
                .getClassLoader()
                .getResource("application.properties");
        assertNotNull(resource,
                "src/test/resources/application.properties must exist to configure the Quarkus test port");

        Properties props = new Properties();
        try (InputStream in = resource.openStream()) {
            props.load(in);
        }

        assertEquals("0", props.getProperty("quarkus.http.test-port"),
                "quarkus.http.test-port must be '0' so Quarkus picks a random free port. "
                + "A fixed port (default: 8081) conflicts with a NEXUS container mapped to "
                + "127.0.0.1:8081->8080, making the build non-hermetic.");
    }
}
