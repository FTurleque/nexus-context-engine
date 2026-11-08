package com.nexus.index.minos;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Borne de transport du payload MINOS (ARCH-0080). La CLI lit par la façade, qui
 * délègue à ce lecteur : la borne est celle du contrat, sans valeur propre à la CLI.
 */
class MinosPayloadTransportBoundTest {

    @Test
    void transportBoundIsTheAdr0044Value() {
        assertEquals(128L * 1024L * 1024L, MinosCodeIndexImporter.MAX_EXPORT_BYTES);
    }

    @Test
    void readerAcceptsExactlyTheBoundAndRejectsOneByteMore() throws IOException {
        long bound = 4096L;

        assertEquals(bound, MinosPayloadReader.readPayload(streamOf(bound), bound).length());

        IOException failure = assertThrows(IOException.class,
                () -> MinosPayloadReader.readPayload(streamOf(bound + 1L), bound));
        assertTrue(failure.getMessage().contains("128 MiB transport limit"), failure.getMessage());
    }

    @Test
    void readerCountsEncodedBytesNotCharacters() throws IOException {
        long bound = 4096L;
        byte[] twoBytePerChar = "é".repeat((int) bound / 2).getBytes(StandardCharsets.UTF_8);
        assertEquals(bound, twoBytePerChar.length);

        assertEquals(bound / 2, MinosPayloadReader.readPayload(new ByteArrayInputStream(twoBytePerChar), bound).length());

        byte[] oneMore = Arrays.copyOf(twoBytePerChar, twoBytePerChar.length + 1);
        oneMore[oneMore.length - 1] = 'x';
        assertThrows(IOException.class,
                () -> MinosPayloadReader.readPayload(new ByteArrayInputStream(oneMore), bound));
    }

    private static InputStream streamOf(long bytes) {
        byte[] data = new byte[(int) bytes];
        Arrays.fill(data, (byte) 'x');
        return new ByteArrayInputStream(data);
    }
}
