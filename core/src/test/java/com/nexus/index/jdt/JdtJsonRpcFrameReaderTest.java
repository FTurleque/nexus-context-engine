package com.nexus.index.jdt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtJsonRpcFrameReaderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void readsAValidFrame() throws Exception {
        byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}".getBytes(StandardCharsets.UTF_8);
        byte[] frame = frame("Content-Length: " + body.length + "\r\n\r\n", body);

        JsonNode message = JdtJsonRpcFrameReader.read(stream(frame), JSON);

        assertNotNull(message);
        assertEquals("2.0", message.path("jsonrpc").asText());
        assertEquals(1, message.path("id").asInt());
    }

    @Test
    void rejectsOversizedContentLengthBeforeAllocatingPayload() {
        String header = "Content-Length: " + (JdtJsonRpcFrameReader.MAX_MESSAGE_BYTES + 1) + "\r\n\r\n";
        BufferedInputStream input = stream(header.getBytes(StandardCharsets.US_ASCII));

        IOException failure = assertThrows(IOException.class,
                () -> JdtJsonRpcFrameReader.read(input, JSON));

        assertTrue(failure.getMessage().contains("trop volumineuse"));
    }

    @Test
    void rejectsOversizedHeaderLine() {
        String header = "X-Fill: " + "a".repeat(JdtJsonRpcFrameReader.MAX_HEADER_LINE_BYTES) + "\r\n"
                + "Content-Length: 2\r\n\r\n{}";
        BufferedInputStream input = stream(header.getBytes(StandardCharsets.US_ASCII));

        IOException failure = assertThrows(IOException.class,
                () -> JdtJsonRpcFrameReader.read(input, JSON));

        assertTrue(failure.getMessage().contains("Ligne d'en-tête"));
    }

    @Test
    void rejectsConflictingContentLengths() {
        byte[] frame = ("Content-Length: 2\r\nContent-Length: 3\r\n\r\n{}")
                .getBytes(StandardCharsets.US_ASCII);
        BufferedInputStream input = stream(frame);

        IOException failure = assertThrows(IOException.class,
                () -> JdtJsonRpcFrameReader.read(input, JSON));

        assertTrue(failure.getMessage().contains("contradictoires"));
    }

    @Test
    void rejectsMalformedContentLength() {
        byte[] frame = "Content-Length: lots\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII);
        BufferedInputStream input = stream(frame);

        IOException failure = assertThrows(IOException.class,
                () -> JdtJsonRpcFrameReader.read(input, JSON));

        assertTrue(failure.getMessage().contains("invalide"));
    }

    @Test
    void rejectsHeaderTruncatedBeforeLineTerminator() {
        byte[] frame = "Content-Length: 2".getBytes(StandardCharsets.US_ASCII);
        BufferedInputStream input = stream(frame);

        IOException failure = assertThrows(IOException.class,
                () -> JdtJsonRpcFrameReader.read(input, JSON));

        assertTrue(failure.getMessage().contains("tronqué"));
    }

    private static BufferedInputStream stream(byte[] bytes) {
        return new BufferedInputStream(new ByteArrayInputStream(bytes));
    }

    private static byte[] frame(String header, byte[] body) {
        byte[] prefix = header.getBytes(StandardCharsets.US_ASCII);
        byte[] result = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(body, 0, result, prefix.length, body.length);
        return result;
    }
}
