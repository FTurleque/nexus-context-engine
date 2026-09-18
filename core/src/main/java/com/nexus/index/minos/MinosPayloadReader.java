package com.nexus.index.minos;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static com.nexus.index.minos.MinosCodeIndexImporter.*;

/** Composant interne du contrat d’import MINOS. */
final class MinosPayloadReader {
    private static final int READER_BUFFER_CHARS = 16 * 1024;
    static String readPayload(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        Reader reader = new InputStreamReader(new BoundedPayloadInputStream(input), StandardCharsets.UTF_8);
        StringBuilder output = new StringBuilder(READER_BUFFER_CHARS);
        char[] buffer = new char[READER_BUFFER_CHARS];
        int read;
        while ((read = reader.read(buffer)) >= 0) {
            if (read > 0) {
                output.append(buffer, 0, read);
            }
        }
        return output.toString();
    }

    static void requireTransportSize(String payload) throws IOException {
        long bytes = 0L;
        for (int index = 0; index < payload.length(); index++) {
            char character = payload.charAt(index);
            if (character <= 0x7F) {
                bytes += 1L;
            } else if (character <= 0x7FF) {
                bytes += 2L;
            } else if (Character.isHighSurrogate(character)
                    && index + 1 < payload.length()
                    && Character.isLowSurrogate(payload.charAt(index + 1))) {
                bytes += 4L;
                index++;
            } else {
                // Conservative for non-BMP-invalid/unpaired UTF-16 input: never undercount.
                bytes += 3L;
            }
            if (bytes > MAX_EXPORT_BYTES) {
                throw transportTooLarge();
            }
        }
    }

    static IOException transportTooLarge() {
        return new IOException("MINOS export exceeds the 128 MiB transport limit");
    }
    private static final class BoundedPayloadInputStream extends FilterInputStream {

        private long consumed;

        private BoundedPayloadInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                record(1L);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            long remaining = MAX_EXPORT_BYTES - consumed;
            int requested = (int) Math.min((long) length, remaining + 1L);
            int read = super.read(buffer, offset, requested);
            if (read > 0) {
                record(read);
            }
            return read;
        }

        private void record(long bytes) throws IOException {
            if (bytes > MAX_EXPORT_BYTES - consumed) {
                throw transportTooLarge();
            }
            consumed += bytes;
        }
    }
}
