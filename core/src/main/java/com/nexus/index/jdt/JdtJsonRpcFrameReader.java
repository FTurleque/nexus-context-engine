package com.nexus.index.jdt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * Lecteur défensif de frames JSON-RPC/LSP provenant du processus JDT LS.
 *
 * <p>Toutes les tailles sont bornées avant allocation afin qu'un serveur défectueux ou compromis
 * ne puisse pas provoquer une consommation mémoire non bornée via {@code Content-Length} ou des
 * en-têtes volontairement gigantesques.</p>
 */
final class JdtJsonRpcFrameReader {

    static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;
    static final int MAX_HEADER_BYTES = 64 * 1024;
    static final int MAX_HEADER_LINE_BYTES = 8 * 1024;
    static final int MAX_PENDING_MESSAGES = 256;

    private JdtJsonRpcFrameReader() {
    }

    static JsonNode read(BufferedInputStream input, ObjectMapper mapper) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(mapper, "mapper");

        int contentLength = -1;
        int headerBytes = 0;
        while (true) {
            HeaderLine headerLine = readHeaderLine(input, headerBytes);
            if (headerLine == null) {
                return null;
            }
            headerBytes += headerLine.consumedBytes();
            if (headerBytes > MAX_HEADER_BYTES) {
                throw new IOException("En-têtes JSON-RPC JDT LS trop volumineux");
            }

            String line = headerLine.value();
            if (line.isEmpty()) {
                break;
            }
            int separator = line.indexOf(':');
            if (separator <= 0 || !"content-length".equalsIgnoreCase(line.substring(0, separator).trim())) {
                continue;
            }

            int parsedLength;
            try {
                parsedLength = Integer.parseInt(line.substring(separator + 1).trim());
            } catch (NumberFormatException invalidLength) {
                throw new IOException("Content-Length JDT LS invalide", invalidLength);
            }
            if (contentLength >= 0 && contentLength != parsedLength) {
                throw new IOException("En-têtes Content-Length JDT LS contradictoires");
            }
            contentLength = parsedLength;
        }

        if (contentLength <= 0) {
            throw new IOException("En-tête Content-Length absent ou invalide dans la réponse JDT LS");
        }
        if (contentLength > MAX_MESSAGE_BYTES) {
            throw new IOException(
                    "Réponse JDT LS trop volumineuse : " + contentLength
                            + " octets > limite " + MAX_MESSAGE_BYTES);
        }

        byte[] payload = input.readNBytes(contentLength);
        if (payload.length != contentLength) {
            throw new IOException("Réponse JDT LS tronquée");
        }
        return mapper.readTree(payload);
    }

    private static HeaderLine readHeaderLine(BufferedInputStream stream, int alreadyConsumed) throws IOException {
        StringBuilder line = new StringBuilder();
        int consumed = 0;
        while (true) {
            int value = stream.read();
            if (value < 0) {
                return endOfStream(line, consumed);
            }
            consumed++;
            validateHeaderLineSize(alreadyConsumed, consumed);
            if (value == '\n') {
                return completedHeaderLine(line, consumed);
            }
            line.append((char) value);
        }
    }

    private static HeaderLine endOfStream(StringBuilder line, int consumed) throws IOException {
        if (line.isEmpty() && consumed == 0) {
            return null;
        }
        throw new IOException("En-tête JSON-RPC JDT LS tronqué");
    }

    private static void validateHeaderLineSize(int alreadyConsumed, int consumed) throws IOException {
        if (alreadyConsumed + consumed > MAX_HEADER_BYTES) {
            throw new IOException("En-têtes JSON-RPC JDT LS trop volumineux");
        }
        if (consumed > MAX_HEADER_LINE_BYTES) {
            throw new IOException("Ligne d'en-tête JSON-RPC JDT LS trop volumineuse");
        }
    }

    private static HeaderLine completedHeaderLine(StringBuilder line, int consumed) {
        int length = line.length();
        if (length > 0 && line.charAt(length - 1) == '\r') {
            line.setLength(length - 1);
        }
        return new HeaderLine(line.toString(), consumed);
    }

    private record HeaderLine(String value, int consumedBytes) {
    }
}
