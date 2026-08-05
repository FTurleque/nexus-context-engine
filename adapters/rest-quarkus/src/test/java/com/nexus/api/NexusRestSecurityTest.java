package com.nexus.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusRestSecurityTest {

    @Test
    void recognizesLoopbackWithoutTreatingWildcardBindingsAsLocal() {
        assertTrue(NexusRestSecurity.isLoopbackHost("127.0.0.1"));
        assertTrue(NexusRestSecurity.isLoopbackHost("localhost"));
        assertTrue(NexusRestSecurity.isLoopbackHost("::1"));
        assertFalse(NexusRestSecurity.isLoopbackHost("0.0.0.0"));
    }

    @Test
    void validatesBearerTokensExactly() {
        assertTrue(NexusRestSecurity.tokenMatches("secret-value", "Bearer secret-value"));
        assertTrue(NexusRestSecurity.tokenMatches("secret-value", "bearer secret-value"));
        assertFalse(NexusRestSecurity.tokenMatches("secret-value", null));
        assertFalse(NexusRestSecurity.tokenMatches("secret-value", "secret-value"));
        assertFalse(NexusRestSecurity.tokenMatches("secret-value", "Bearer wrong"));
    }
}
