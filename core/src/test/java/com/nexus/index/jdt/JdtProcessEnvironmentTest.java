package com.nexus.index.jdt;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtProcessEnvironmentTest {

    @Test
    void keepsOnlyTheExplicitRuntimeAllowlist() {
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", "/usr/bin");
        environment.put("JAVA_HOME", "/opt/java");
        environment.put("HOME", "/home/nexus");
        environment.put("NEXUS_REST_API_TOKEN", "secret");
        environment.put("AWS_SECRET_ACCESS_KEY", "secret");
        environment.put("GITHUB_TOKEN", "secret");
        environment.put("JAVA_TOOL_OPTIONS", "-javaagent:/tmp/untrusted.jar");
        environment.put("JDK_JAVA_OPTIONS", "-Dsecret=value");
        environment.put("SSH_AUTH_SOCK", "/tmp/agent.sock");
        environment.put("CLIENT_PORT", "1234");

        ProcessBuilder.sanitizeEnvironment(environment);

        assertTrue(environment.containsKey("PATH"));
        assertTrue(environment.containsKey("JAVA_HOME"));
        assertTrue(environment.containsKey("HOME"));
        assertFalse(environment.containsKey("NEXUS_REST_API_TOKEN"));
        assertFalse(environment.containsKey("AWS_SECRET_ACCESS_KEY"));
        assertFalse(environment.containsKey("GITHUB_TOKEN"));
        assertFalse(environment.containsKey("JAVA_TOOL_OPTIONS"));
        assertFalse(environment.containsKey("JDK_JAVA_OPTIONS"));
        assertFalse(environment.containsKey("SSH_AUTH_SOCK"));
        assertFalse(environment.containsKey("CLIENT_PORT"));
    }
}
