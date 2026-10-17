package com.nexus.index.jdt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.nexus.index.jdt.JdtLanguageServerCodeIntelligenceProvider.Configuration;
import com.nexus.index.jdt.JdtLanguageServerCodeIntelligenceProvider.Session;

final class JdtStdioSession implements Session {

    private static final String JSON_RPC_VERSION = "2.0";
    private static final int STDERR_TAIL_SIZE = 50;
    private static final int MAX_STDERR_LINE_CHARS = 4 * 1024;
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(1);

    private final Configuration configuration;
    private final Path projectRoot;
    private final ObjectMapper mapper;
    private final Process process;
    private final BufferedInputStream input;
    private final OutputStream output;
    private final BlockingQueue<Inbound> inbox;
    private final AtomicLong nextRequestId;
    private final Deque<String> stderrTail;
    private final Object writeLock;
    private volatile boolean serviceReady;
    private volatile boolean timedOut;

    JdtStdioSession(
            Configuration configuration,
            Path projectRoot,
            Process process) {
        this.configuration = configuration;
        this.projectRoot = projectRoot;
        this.mapper = new ObjectMapper();
        this.process = process;
        this.input = new BufferedInputStream(process.getInputStream());
        this.output = process.getOutputStream();
        this.inbox = new LinkedBlockingQueue<>(JdtJsonRpcFrameReader.MAX_PENDING_MESSAGES);
        this.nextRequestId = new AtomicLong(1L);
        this.stderrTail = new ArrayDeque<>();
        this.writeLock = new Object();
        Thread.ofVirtual().name("nexus-jdtls-stdout").start(this::readLoop);
        Thread.ofVirtual().name("nexus-jdtls-stderr").start(this::drainStderr);
    }

    static JdtStdioSession open(Configuration configuration, Path projectRoot) throws IOException {
        return new JdtStdioSession(configuration, projectRoot, JdtProcessLauncher.start(configuration, projectRoot));
    }

    @Override
    public void initialize() throws IOException {
        ObjectNode params = JdtWorkspaceMessages.initialize(mapper, projectRoot);
        request("initialize", params);
        notify("initialized", mapper.createObjectNode());
        ObjectNode configurationChanged = mapper.createObjectNode();
        configurationChanged.set("settings", mapper.createObjectNode());
        notify("workspace/didChangeConfiguration", configurationChanged);
        awaitServiceReady();
    }

    @Override
    public JsonNode request(String method, JsonNode params) throws IOException {
        return request(method, params, configuration.timeout());
    }

    private JsonNode request(String method, JsonNode params, Duration timeout) throws IOException {
        long id = nextRequestId.getAndIncrement();
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", JSON_RPC_VERSION);
        message.put("id", id);
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        writeMessage(message);
        return awaitResponse(id, timeout);
    }

    @Override
    public void notify(String method, JsonNode params) throws IOException {
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", JSON_RPC_VERSION);
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }
        writeMessage(message);
    }

    @Override
    public void close() {
        try {
            if (process.isAlive() && !timedOut) {
                try {
                    request("shutdown", NullNode.instance, shutdownTimeout(configuration.timeout()));
                } catch (IOException ignored) {
                    // Le processus peut déjà être en train de se terminer. Un shutdown
                    // bloqué ne consomme jamais le timeout opérationnel complet.
                }
            }
            if (process.isAlive()) {
                try {
                    notify("exit", NullNode.instance);
                } catch (IOException ignored) {
                    // Le flux peut déjà être fermé.
                }
            }
        } finally {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    static Duration shutdownTimeout(Duration operationTimeout) {
        Objects.requireNonNull(operationTimeout, "operationTimeout");
        return operationTimeout.compareTo(SHUTDOWN_TIMEOUT) <= 0
                ? operationTimeout
                : SHUTDOWN_TIMEOUT;
    }

    private JsonNode awaitResponse(long expectedId, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            JsonNode message = nextMessage(deadline, timeout);
            observeNotification(message);
            if (isServerRequest(message)) {
                respondToServerRequest(message);
                continue;
            }
            if (!message.has("id") || !message.path("id").canConvertToLong()
                    || message.path("id").asLong() != expectedId) {
                continue;
            }
            if (message.has("error")) {
                throw new IOException("JDT LS a rejeté la requête : " + message.path("error"));
            }
            return message.has("result") ? message.path("result") : NullNode.instance;
        }
    }

    private void awaitServiceReady() throws IOException {
        if (serviceReady) {
            return;
        }
        Duration timeout = configuration.timeout();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!serviceReady) {
            JsonNode message = nextMessage(deadline, timeout);
            observeNotification(message);
            if (isServerRequest(message)) {
                respondToServerRequest(message);
            }
        }
    }

    private JsonNode nextMessage(long deadline, Duration timeout) throws IOException {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0L) {
            throw timeoutFailure(timeout);
        }
        try {
            Inbound inbound = inbox.poll(remainingNanos, TimeUnit.NANOSECONDS);
            if (inbound == null) {
                throw timeoutFailure(timeout);
            }
            if (inbound.failure() != null) {
                throw new IOException("Connexion JDT LS interrompue. " + stderrSummary(), inbound.failure());
            }
            return inbound.message();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Attente JDT LS interrompue", interrupted);
        }
    }

    private IOException timeoutFailure(Duration timeout) {
        timedOut = true;
        return new IOException(
                "Délai JDT LS dépassé après " + timeout.toSeconds() + " s. " + stderrSummary());
    }

    private void observeNotification(JsonNode message) {
        if (!"language/status".equals(message.path("method").asText())) {
            return;
        }
        JsonNode params = message.path("params");
        if ("ServiceReady".equals(params.path("type").asText())
                || "ServiceReady".equals(params.path("message").asText())) {
            serviceReady = true;
        }
    }

    private static boolean isServerRequest(JsonNode message) {
        return message.has("method") && message.has("id");
    }

    private void respondToServerRequest(JsonNode request) throws IOException {
        String method = request.path("method").asText();
        JsonNode result;
        if ("workspace/configuration".equals(method)) {
            ArrayNode values = mapper.createArrayNode();
            JsonNode items = request.path("params").path("items");
            int count = items.isArray() ? items.size() : 0;
            for (int index = 0; index < count; index++) {
                values.add(mapper.createObjectNode());
            }
            result = values;
        } else if ("workspace/workspaceFolders".equals(method)) {
            ArrayNode folders = mapper.createArrayNode();
            ObjectNode folder = mapper.createObjectNode();
            folder.put("uri", projectRoot.toUri().toString());
            folder.put("name", projectRoot.getFileName() == null ? "project" : projectRoot.getFileName().toString());
            folders.add(folder);
            result = folders;
        } else if ("workspace/applyEdit".equals(method)) {
            ObjectNode applied = mapper.createObjectNode();
            applied.put("applied", false);
            applied.put("failureReason", "Le provider JDT NEXUS fonctionne en lecture seule");
            result = applied;
        } else {
            result = NullNode.instance;
        }

        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", JSON_RPC_VERSION);
        response.set("id", request.path("id"));
        response.set("result", result);
        writeMessage(response);
    }

    private void writeMessage(JsonNode message) throws IOException {
        byte[] body = mapper.writeValueAsBytes(message);
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        synchronized (writeLock) {
            output.write(header);
            output.write(body);
            output.flush();
        }
    }

    private void readLoop() {
        try {
            while (true) {
                JsonNode message = readFrame();
                if (message == null) {
                    failInbound(new IOException("Flux stdout JDT LS fermé"));
                    return;
                }
                if (!inbox.offer(new Inbound(message, null))) {
                    failInbound(new IOException(
                            "File de messages JDT LS saturée (maximum "
                                    + JdtJsonRpcFrameReader.MAX_PENDING_MESSAGES + ")"));
                    return;
                }
            }
        } catch (IOException exception) {
            failInbound(exception);
        }
    }

    private JsonNode readFrame() throws IOException {
        return JdtJsonRpcFrameReader.read(input, mapper);
    }

    private void failInbound(IOException failure) {
        inbox.clear();
        if (!inbox.offer(new Inbound(null, failure))) {
            process.destroyForcibly();
            return;
        }
        process.destroy();
    }

    private void drainStderr() {
        try (InputStreamReader reader = new InputStreamReader(
                process.getErrorStream(), StandardCharsets.UTF_8)) {
            BoundedLineDrain.drain(reader, MAX_STDERR_LINE_CHARS, this::recordStderrLine);
        } catch (IOException ignored) {
            // Le processus ferme naturellement stderr à l'arrêt.
        }
    }

    private void recordStderrLine(String line) {
        synchronized (stderrTail) {
            if (stderrTail.size() >= STDERR_TAIL_SIZE) {
                stderrTail.removeFirst();
            }
            stderrTail.addLast(line);
        }
    }

    private String stderrSummary() {
        synchronized (stderrTail) {
            if (stderrTail.isEmpty()) {
                return "Aucun diagnostic stderr JDT LS disponible.";
            }
            return "Derniers diagnostics JDT LS : " + String.join(" | ", stderrTail);
        }
    }

    private record Inbound(JsonNode message, IOException failure) {
    }
}
