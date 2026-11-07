package com.nexus.api;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Règle pure du contrôle de transport loopback (AUD-SEC-04). */
class NexusRestLoopbackTransportFilterTest {

    @Test
    void acceptsLiteralLoopbackHostsWithOrWithoutPort() {
        for (String host : new String[]{
                "127.0.0.1", "127.0.0.1:8080", "localhost", "LOCALHOST:65535", "[::1]", "[::1]:8080"}) {
            assertTrue(NexusRestLoopbackTransportFilter.isLoopbackAuthority(host), host);
        }
    }

    @Test
    void rejectsNonLoopbackMissingOrMalformedHosts() {
        for (String host : new String[]{
                null, "", " ", "evil.example", "evil.example:8080", "localhost.evil.example",
                "127.0.0.1.nip.io", "localhost.", "127.0.0.2", "0.0.0.0", "::1", "[::1", "[::2]:8080",
                "localhost:", "localhost:80a", "localhost:123456", "127.0.0.1:8080:9", "user@localhost",
                "127.0.0.1, evil.example"}) {
            assertFalse(NexusRestLoopbackTransportFilter.isLoopbackAuthority(host), String.valueOf(host));
        }
    }

    @Test
    void acceptsRequestsWithoutOriginOrWithLoopbackOrigin() {
        assertTrue(NexusRestLoopbackTransportFilter.isAcceptedOnLoopbackListener("127.0.0.1:8080", null));
        for (String origin : new String[]{
                "http://localhost:3000", "https://127.0.0.1", "http://[::1]:8080", "HTTP://LOCALHOST"}) {
            assertTrue(NexusRestLoopbackTransportFilter.isAcceptedOnLoopbackListener("localhost:8080", origin),
                    origin);
        }
    }

    @Test
    void rejectsNonLoopbackOrOpaqueOrigins() {
        for (String origin : new String[]{
                "http://evil.example", "https://evil.example:8080", "null", "", "file://localhost",
                "http://localhost/path", "http://localhost.evil.example", "http://user@localhost",
                "http://localhost, http://evil.example"}) {
            assertFalse(NexusRestLoopbackTransportFilter.isAcceptedOnLoopbackListener("localhost:8080", origin),
                    origin);
        }
    }

    @Test
    void rejectsNonLoopbackHostEvenWithLoopbackOrigin() {
        assertFalse(NexusRestLoopbackTransportFilter.isAcceptedOnLoopbackListener(
                "evil.example", "http://localhost:3000"));
        assertFalse(NexusRestLoopbackTransportFilter.isAcceptedOnLoopbackListener(null, null));
    }

    @Test
    void filterIsInactiveWhenTheListenerIsNotLoopback() {
        NexusRestLoopbackTransportFilter filter = new NexusRestLoopbackTransportFilter("0.0.0.0");
        jakarta.ws.rs.container.ContainerRequestContext request =
                (jakarta.ws.rs.container.ContainerRequestContext) java.lang.reflect.Proxy.newProxyInstance(
                        jakarta.ws.rs.container.ContainerRequestContext.class.getClassLoader(),
                        new Class<?>[]{jakarta.ws.rs.container.ContainerRequestContext.class},
                        (proxy, method, args) -> {
                            throw new AssertionError("Hors loopback, le filtre ne lit aucun en-tête : "
                                    + method.getName());
                        });

        filter.filter(request);
    }

    @Test
    void activationStaysOnForLoopbackBlankOrUnresolvableListenerHosts() {
        for (String host : new String[]{"127.0.0.1", "localhost", "LOCALHOST", "::1", "127.0.0.2", null, "", " "}) {
            assertTrue(new NexusRestLoopbackTransportFilter(host).active(), String.valueOf(host));
        }
        // Un nom en .invalid ne se résout jamais (RFC 6761) : l'échec de résolution garde le filtre actif.
        assertTrue(new NexusRestLoopbackTransportFilter("nexus-listener.invalid").active());
        assertFalse(new NexusRestLoopbackTransportFilter("0.0.0.0").active());
    }

    @Test
    void unresolvableListenerHostKeepsRejectingNonLoopbackHostHeaders() {
        NexusRestLoopbackTransportFilter filter = new NexusRestLoopbackTransportFilter("nexus-listener.invalid");
        AtomicReference<Object> aborted = new AtomicReference<>();
        ContainerRequestContext request = (ContainerRequestContext) Proxy.newProxyInstance(
                ContainerRequestContext.class.getClassLoader(),
                new Class<?>[]{ContainerRequestContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHeaderString" -> "Host".equals(args[0]) ? "evil.example" : null;
                    case "abortWith" -> {
                        aborted.set(args[0]);
                        yield null;
                    }
                    default -> throw new AssertionError(method.getName());
                });

        filter.filter(request);

        Response response = assertInstanceOf(Response.class, aborted.get());
        assertEquals(403, response.getStatus());
    }
}
