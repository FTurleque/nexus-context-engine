package com.nexus.search.semantic.ollama;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OllamaEndpointResolverTest {

    @Test
    void nativeRuntimeNeverRewritesLoopback() {
        URI uri = URI.create("http://127.0.0.1:11434");
        assertEquals(uri, OllamaEndpointResolver.resolveForRuntime(uri, false));
    }

    @Test
    void dockerRuntimeRewritesIpv4LoopbackToHostGateway() {
        URI resolved = OllamaEndpointResolver.resolveForRuntime(
                URI.create("http://127.0.0.1:11434"), true);
        assertEquals(URI.create("http://host.docker.internal:11434"), resolved);
    }

    @Test
    void dockerRuntimeRewritesLocalhostToHostGateway() {
        URI resolved = OllamaEndpointResolver.resolveForRuntime(
                URI.create("http://localhost:11434"), true);
        assertEquals(URI.create("http://host.docker.internal:11434"), resolved);
    }

    @Test
    void dockerRuntimeRewritesLoopbackButPreservesPathAndPort() {
        URI resolved = OllamaEndpointResolver.resolveForRuntime(
                URI.create("http://127.0.0.1:9999/api"), true);
        assertEquals(URI.create("http://host.docker.internal:9999/api"), resolved);
    }

    @Test
    void rejectsRemoteHttpByDefault() {
        assertThrows(IllegalArgumentException.class, () -> OllamaEndpointResolver.resolveForRuntime(
                URI.create("http://ollama.internal.example.com:11434"), true));
        assertThrows(IllegalArgumentException.class, () -> OllamaEndpointResolver.resolveForRuntime(
                URI.create("http://10.1.2.3:11434"), false));
    }

    @Test
    void preservesRemoteHttps() {
        URI dns = URI.create("https://ollama.internal.example.com:11434");
        URI ipv6 = URI.create("https://[2001:db8::1]:11434");
        assertEquals(dns, OllamaEndpointResolver.resolveForRuntime(dns, true));
        assertEquals(ipv6, OllamaEndpointResolver.resolveForRuntime(ipv6, true));
    }

    @Test
    void explicitOptInAllowsRemoteHttp() {
        URI uri = URI.create("http://my-ollama:11434");
        assertEquals(uri, OllamaEndpointResolver.resolveForRuntime(uri, true, true));
    }

    @Test
    void dockerRuntimeRewritesIpv6Loopback() {
        URI resolved = OllamaEndpointResolver.resolveForRuntime(
                URI.create("http://[::1]:11434"), true);
        assertEquals(URI.create("http://host.docker.internal:11434"), resolved);
    }

    @Test
    void rejectsCredentialsEmbeddedInEndpointUri() {
        assertThrows(IllegalArgumentException.class, () -> OllamaEndpointResolver.resolveForRuntime(
                URI.create("https://user:password@ollama.example.com:11434"), false));
    }
}
