package com.nexus.api;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NexusRestAuthenticationFilterTest {

    private final NexusRestAuthenticationFilter filter = new NexusRestAuthenticationFilter();

    @Test
    void allowsRequestsWhenNoTokenIsConfigured() throws Exception {
        String previous = System.getProperty(NexusRestSecurity.TOKEN_PROPERTY);
        try {
            System.clearProperty(NexusRestSecurity.TOKEN_PROPERTY);
            AtomicReference<Response> aborted = new AtomicReference<>();

            filter.filter(request("Bearer anything", aborted));

            assertNull(aborted.get());
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    void acceptsTheConfiguredBearerToken() throws Exception {
        String previous = System.getProperty(NexusRestSecurity.TOKEN_PROPERTY);
        try {
            System.setProperty(NexusRestSecurity.TOKEN_PROPERTY, "test-token");
            AtomicReference<Response> aborted = new AtomicReference<>();

            filter.filter(request("Bearer test-token", aborted));

            assertNull(aborted.get());
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    void rejectsMissingOrInvalidBearerTokenWithStructuredUnauthorizedResponse() throws Exception {
        String previous = System.getProperty(NexusRestSecurity.TOKEN_PROPERTY);
        try {
            System.setProperty(NexusRestSecurity.TOKEN_PROPERTY, "test-token");
            for (String authorization : new String[]{null, "Bearer wrong"}) {
                AtomicReference<Response> aborted = new AtomicReference<>();

                filter.filter(request(authorization, aborted));

                Response response = aborted.get();
                assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
                assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
                @SuppressWarnings("unchecked")
                Map<String, String> body = (Map<String, String>) response.getEntity();
                assertEquals("UNAUTHORIZED", body.get("error"));
                assertEquals("Bearer token NEXUS manquant ou invalide", body.get("message"));
            }
        } finally {
            restoreProperty(previous);
        }
    }

    private static ContainerRequestContext request(
            String authorization,
            AtomicReference<Response> aborted) {
        return (ContainerRequestContext) Proxy.newProxyInstance(
                ContainerRequestContext.class.getClassLoader(),
                new Class<?>[]{ContainerRequestContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHeaderString" -> authorization;
                    case "abortWith" -> {
                        aborted.set((Response) args[0]);
                        yield null;
                    }
                    case "toString" -> "NexusRestAuthenticationFilterTestContext";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        return null;
    }

    private static void restoreProperty(String previous) {
        if (previous == null) {
            System.clearProperty(NexusRestSecurity.TOKEN_PROPERTY);
        } else {
            System.setProperty(NexusRestSecurity.TOKEN_PROPERTY, previous);
        }
    }
}
