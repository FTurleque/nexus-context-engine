package com.nexus.index.scip;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** Composant interne de lecture SCIP bornée. */
final class ScipWireInput {
    static long readVarintOrEof(InputStream input) throws IOException {
        int first = input.read();
        if (first < 0) {
            return -1L;
        }
        long value = first & 0x7fL;
        if ((first & 0x80) == 0) {
            return value;
        }
        int shift = 7;
        for (int index = 1; index < 10; index++) {
            int next = input.read();
            if (next < 0) {
                throw new EOFException("Varint SCIP tronqué");
            }
            value |= (long) (next & 0x7f) << shift;
            if ((next & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IOException("Varint SCIP invalide");
    }

    static byte[] readLengthDelimited(InputStream input, int maxMessageBytes) throws IOException {
        long rawLength = readRequiredVarint(input);
        if (rawLength > maxMessageBytes) {
            throw new IOException(
                    "Message SCIP trop volumineux : " + rawLength
                            + " octets (maximum " + maxMessageBytes + ")");
        }
        int length = checkedInt(rawLength, "longueur SCIP");
        byte[] payload = input.readNBytes(length);
        if (payload.length != length) {
            throw new EOFException("Message SCIP tronqué");
        }
        return payload;
    }

    static long readRequiredVarint(InputStream input) throws IOException {
        long value = readVarintOrEof(input);
        if (value < 0) {
            throw new EOFException("Varint SCIP attendu");
        }
        return value;
    }

    static void skipField(InputStream input, int wireType) throws IOException {
        switch (wireType) {
            case WireType.VARINT -> readRequiredVarint(input);
            case WireType.FIXED_64 -> skipFully(input, 8);
            case WireType.LENGTH_DELIMITED -> skipFully(input, checkedInt(readRequiredVarint(input), "longueur SCIP"));
            case WireType.FIXED_32 -> skipFully(input, 4);
            default -> throw new IOException("Type de fil Protobuf SCIP non supporté : " + wireType);
        }
    }

    static void skipFully(InputStream input, int length) throws IOException {
        int remaining = length;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= checkedInt(skipped, "octets ignorés");
                continue;
            }
            if (input.read() < 0) {
                throw new EOFException("Message SCIP tronqué");
            }
            remaining--;
        }
    }

    static int checkedInt(long value, String label) throws IOException {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IOException(label + " hors limites : " + value);
        }
        return (int) value;
    }

    static final class WireType {
        static final int VARINT = 0;
        static final int FIXED_64 = 1;
        static final int LENGTH_DELIMITED = 2;
        static final int FIXED_32 = 5;

        private WireType() {
        }
    }

}
