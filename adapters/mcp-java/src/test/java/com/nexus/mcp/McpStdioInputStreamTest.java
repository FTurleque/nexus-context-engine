package com.nexus.mcp;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpStdioInputStreamTest {

    @Test
    void acceptsMultipleFramesBeyondTheCumulativeLimit() throws Exception {
        byte[] payload = "1234\n5678\n".getBytes(StandardCharsets.UTF_8);
        AtomicInteger terminalSignals = new AtomicInteger();
        try (McpStdioInputStream input = new McpStdioInputStream(
                new ByteArrayInputStream(payload), 4, terminalSignals::incrementAndGet)) {
            assertArrayEquals(payload, input.readAllBytes());
        }
        assertEquals(1, terminalSignals.get());
    }

    @Test
    void rejectsOneOversizedFrameAndSignalsTermination() throws Exception {
        byte[] payload = "12345\n".getBytes(StandardCharsets.UTF_8);
        AtomicInteger terminalSignals = new AtomicInteger();
        try (McpStdioInputStream input = new McpStdioInputStream(
                new ByteArrayInputStream(payload), 4, terminalSignals::incrementAndGet)) {
            IOException failure = assertThrows(IOException.class, input::readAllBytes);
            assertTrue(failure.getMessage().contains("maximum 4 octets"));
        }
        assertEquals(1, terminalSignals.get());
    }

    @Test
    void signalsEndOfInputOnlyOnce() throws Exception {
        AtomicInteger terminalSignals = new AtomicInteger();
        try (McpStdioInputStream input = new McpStdioInputStream(
                new ByteArrayInputStream(new byte[0]), 8, terminalSignals::incrementAndGet)) {
            assertEquals(-1, input.read());
            assertEquals(-1, input.read());
        }
        assertEquals(1, terminalSignals.get());
    }
}
