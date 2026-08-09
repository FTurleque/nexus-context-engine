package com.nexus.search.semantic.ollama;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Non-regression tests for P1: resolving the Ollama endpoint per runtime. Loopback must become
 * {@code host.docker.internal} inside Docker; every other host must pass through untouched in both
 * runtimes so that remote hosts, custom DNS and IPv6 are never broken.
 */
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
    void dockerRuntimePreservesRemoteHost() {
        URI uri = URI.create("http://ollama.internal.example.com:11434");
        assertEquals(uri, OllamaEndpointResolver.resolveForRuntime(uri, true));
    }

    @Test
    void dockerRuntimePreservesExplicitRemoteIp() {
        URI uri = URI.create("http://10.1.2.3:11434");
        assertEquals(uri, OllamaEndpointResolver.resolveForRuntime(uri, true));
    }

    @Test
    void dockerRuntimePreservesNonLoopbackIpv6() {
        URI uri = URI.create("http://[2001:db8::1]:11434");
        assertEquals(uri, OllamaEndpointResolver.resolveForRuntime(uri, true));
    }

    @Test
    void dockerRuntimeRewritesIpv6Loopback() {
        URI resolved = OllamaEndpointResolver.resolveForRuntime(
                URI.create("http://[::1]:11434"), true);
        assertEquals(URI.create("http://host.docker.internal:11434"), resolved);
    }

    @Test
    void dockerRuntimePreservesCustomDnsName() {
        URI uri = URI.create("http://my-ollama:11434");
        assertEquals(uri, OllamaEndpointResolver.resolveForRuntime(uri, true));
    }
}
