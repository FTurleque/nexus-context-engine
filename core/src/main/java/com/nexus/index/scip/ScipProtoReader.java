package com.nexus.index.scip;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.nexus.index.scip.ScipWireInput.*;

/** Composant interne de lecture SCIP bornée. */
final class ScipProtoReader {
    private static final int MAX_LEGACY_RANGE_VALUES = 4;

    private final byte[] data;
    private final int maxMessageBytes;
    int position;

    ScipProtoReader(byte[] data, int maxMessageBytes) {
        this.data = Objects.requireNonNull(data, "data");
        this.maxMessageBytes = maxMessageBytes;
    }

    boolean hasRemaining() {
        return position < data.length;
    }

    int readTag() throws IOException {
        return checkedInt(readVarint(), "tag Protobuf");
    }

    int readInt32(int wireType) throws IOException {
        requireWireType(wireType, WireType.VARINT);
        return checkedInt(readVarint(), "entier Protobuf");
    }

    boolean readBoolean(int wireType) throws IOException {
        requireWireType(wireType, WireType.VARINT);
        return readVarint() != 0;
    }

    String readString(int wireType) throws IOException {
        ScipProtoReader reader = readMessage(wireType);
        return new String(reader.data, StandardCharsets.UTF_8);
    }

    ScipProtoReader readMessage(int wireType) throws IOException {
        requireWireType(wireType, WireType.LENGTH_DELIMITED);
        long rawLength = readVarint();
        if (rawLength > maxMessageBytes) {
            throw new IOException(
                    "Message Protobuf SCIP trop volumineux : " + rawLength
                            + " octets (maximum " + maxMessageBytes + ")");
        }
        int length = checkedInt(rawLength, "longueur Protobuf");
        ensureAvailable(length);
        byte[] payload = new byte[length];
        System.arraycopy(data, position, payload, 0, length);
        position += length;
        return new ScipProtoReader(payload, maxMessageBytes);
    }

    List<Integer> readPackedInt32(int wireType, int maxValues) throws IOException {
        ScipProtoReader packed = readMessage(wireType);
        List<Integer> values = new ArrayList<>(Math.min(MAX_LEGACY_RANGE_VALUES, Math.max(0, maxValues)));
        while (packed.hasRemaining()) {
            if (values.size() >= maxValues) {
                throw new IOException("Plage legacy SCIP contient plus de "
                        + MAX_LEGACY_RANGE_VALUES + " entiers");
            }
            values.add(checkedInt(packed.readVarint(), "entier Protobuf compacté"));
        }
        return values;
    }

    void skipField(int wireType) throws IOException {
        switch (wireType) {
            case WireType.VARINT -> readVarint();
            case WireType.FIXED_64 -> skip(8);
            case WireType.LENGTH_DELIMITED -> skip(checkedInt(readVarint(), "longueur Protobuf"));
            case WireType.FIXED_32 -> skip(4);
            default -> throw new IOException("Type de fil Protobuf non supporté : " + wireType);
        }
    }

    private long readVarint() throws IOException {
        long value = 0L;
        int shift = 0;
        for (int index = 0; index < 10; index++) {
            ensureAvailable(1);
            int next = data[position++] & 0xff;
            value |= (long) (next & 0x7f) << shift;
            if ((next & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IOException("Varint Protobuf invalide");
    }

    void skip(int length) throws IOException {
        ensureAvailable(length);
        position += length;
    }

    void ensureAvailable(int length) throws EOFException {
        if (length < 0 || position < 0 || position > data.length || length > data.length - position) {
            throw new EOFException("Message Protobuf SCIP tronqué");
        }
    }

    private static void requireWireType(int actual, int expected) throws IOException {
        if (actual != expected) {
            throw new IOException("Type de fil Protobuf inattendu : " + actual + ", attendu : " + expected);
        }
    }}
