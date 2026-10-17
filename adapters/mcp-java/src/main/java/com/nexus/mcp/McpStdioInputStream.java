package com.nexus.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entrée STDIO MCP bornée par trame JSON-RPC (une trame par ligne).
 *
 * <p>La borne est réinitialisée après chaque LF : un serveur longue durée peut
 * traiter un nombre arbitraire de petites requêtes sans transformer la limite
 * anti-DoS en plafond cumulatif de session.</p>
 */
final class McpStdioInputStream extends FilterInputStream {

    static final int DEFAULT_MAX_FRAME_BYTES = 1024 * 1024;

    private final int maxFrameBytes;
    private final Runnable onTerminal;
    private final AtomicBoolean terminalSignalled = new AtomicBoolean();
    private int frameBytes;

    McpStdioInputStream(InputStream delegate, int maxFrameBytes, Runnable onTerminal) {
        super(Objects.requireNonNull(delegate, "delegate"));
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be greater than zero");
        }
        this.maxFrameBytes = maxFrameBytes;
        this.onTerminal = Objects.requireNonNull(onTerminal, "onTerminal");
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value == -1) {
            signalTerminal();
            return -1;
        }
        account(value);
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = super.read(buffer, offset, length);
        if (read == -1) {
            signalTerminal();
            return -1;
        }
        for (int index = offset; index < offset + read; index++) {
            account(buffer[index] & 0xff);
        }
        return read;
    }

    private void account(int value) throws IOException {
        if (value == '\n') {
            frameBytes = 0;
            return;
        }
        frameBytes++;
        if (frameBytes > maxFrameBytes) {
            signalTerminal();
            throw new IOException(
                    "Message MCP STDIO trop volumineux (maximum " + maxFrameBytes + " octets par trame)");
        }
    }

    private void signalTerminal() {
        if (terminalSignalled.compareAndSet(false, true)) {
            onTerminal.run();
        }
    }
}
