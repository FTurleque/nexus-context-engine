package com.nexus.index.minos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.config.NexusPaths;
import com.nexus.index.CodeIndexImporter;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.CodeSymbol;
import com.nexus.index.IndexedRelation;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.RelationKind;
import com.nexus.index.SymbolKind;
import com.nexus.index.SymbolRelation;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Optional Java-21-side importer for the versioned JSON export produced by MINOS.
 *
 * <p>No MINOS class is linked into NEXUS. When the conventional integration JAR is
 * installed under NEXUS_HOME, NEXUS launches the fixed {@code java} command and the
 * fixed MINOS bridge class. The project root is transported over stdin, never placed
 * on the operating-system command line. Ranking and context selection remain entirely
 * in NEXUS.</p>
 */
public final class MinosCodeIndexImporter implements CodeIndexImporter {

    public static final String SOURCE_PROVIDER = "minos";

    private static final String CONTRACT_VERSION = "1";
    private static final String PRODUCER = "MINOS";
    private static final String JAVA_COMMAND = "java";
    private static final String BRIDGE_MAIN_CLASS = "com.minos.integration.nexus.NexusExportBridgeMain";
    private static final String MINOS_CLASSPATH = "integrations/minos/minos-code-intelligence-all.jar";
    private static final String MINOS_HOME = "integrations/minos/home";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(300);
    private static final long MAX_EXPORT_BYTES = 128L * 1024L * 1024L;

    private final Configuration configuration;
    private final ExportRunner exportRunner;
    private final ObjectMapper objectMapper;

    /** Creates a disabled importer used to purge stale MINOS data when integration is off. */
    public MinosCodeIndexImporter() {
        this(null, null, new ObjectMapper());
    }

    private MinosCodeIndexImporter(Configuration configuration) {
        this(configuration, new ProcessExportRunner(configuration), new ObjectMapper());
    }

    MinosCodeIndexImporter(Configuration configuration, ExportRunner exportRunner, ObjectMapper objectMapper) {
        this.configuration = configuration;
        this.exportRunner = exportRunner;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        if (configuration != null) {
            Objects.requireNonNull(exportRunner, "exportRunner");
        }
    }

    /**
     * Enables MINOS only when its shaded JAR is installed at the conventional path
     * {@code <NEXUS_HOME>/integrations/minos/minos-code-intelligence-all.jar}.
     */
    public static MinosCodeIndexImporter fromPaths(NexusPaths paths) throws IOException {
        Objects.requireNonNull(paths, "paths");
        if (!Files.isRegularFile(paths.minosIntegrationJar())) {
            return new MinosCodeIndexImporter();
        }
        return new MinosCodeIndexImporter(new Configuration(paths.home(), DEFAULT_TIMEOUT));
    }

    public boolean enabled() {
        return configuration != null;
    }

    @Override
    public String sourceProvider() {
        return SOURCE_PROVIDER;
    }

    @Override
    public Optional<CodeIntelligenceSnapshot> importIndex(Path projectRoot) throws IOException {
        if (!enabled()) {
            return Optional.empty();
        }
        Path root = Objects.requireNonNull(projectRoot, "projectRoot").toRealPath();
        String payload = exportRunner.export(root);
        return Optional.of(parseExport(root, payload));
    }

    CodeIntelligenceSnapshot parseExport(Path projectRoot, String payload) throws IOException {
        Path root = Objects.requireNonNull(projectRoot, "projectRoot").toRealPath();
        JsonNode document = objectMapper.readTree(Objects.requireNonNull(payload, "payload"));
        requireText(document, "contractVersion", CONTRACT_VERSION);
        requireText(document, "producer", PRODUCER);

        JsonNode project = requiredObject(document, "project");
        Path exportedRoot = normalizedAbsolutePath(requiredText(project, "rootPath"), "project root");
        if (!root.equals(exportedRoot)) {
            throw new IOException("MINOS export belongs to another project root: " + exportedRoot);
        }

        Map<String, IndexedSymbol> symbols = new LinkedHashMap<>();
        for (JsonNode symbolNode : requiredArray(document, "symbols")) {
            IndexedSymbol symbol = mapSymbol(root, symbolNode);
            if (symbol == null) {
                continue;
            }
            String key = symbol.relativePath() + '\u0000'
                    + symbol.symbol().kind() + '\u0000'
                    + symbol.symbol().name() + '\u0000'
                    + symbol.symbol().startLine();
            symbols.putIfAbsent(key, symbol);
        }

        Map<String, IndexedRelation> relations = new LinkedHashMap<>();
        for (JsonNode relationNode : requiredArray(document, "relations")) {
            IndexedRelation relation = mapRelation(root, relationNode);
            if (relation == null) {
                continue;
            }
            String key = relation.relativePath() + '\u0000'
                    + relation.relation().kind() + '\u0000'
                    + relation.relation().source() + '\u0000'
                    + relation.relation().target();
            relations.putIfAbsent(key, relation);
        }

        return new CodeIntelligenceSnapshot(
                SOURCE_PROVIDER,
                List.copyOf(symbols.values()),
                List.copyOf(relations.values()));
    }

    private static IndexedSymbol mapSymbol(Path root, JsonNode node) throws IOException {
        if (!"RESOLVED".equals(optionalText(node, "resolutionStatus"))) {
            return null;
        }
        SymbolKind kind = mapSymbolKind(optionalText(node, "kind"));
        if (kind == null) {
            return null;
        }
        String relativePath = safeRelativePath(root, requiredText(node, "filePath"));
        if (relativePath == null) {
            return null;
        }
        int startLine = positiveLine(node, "startLine");
        int endLine = positiveLine(node, "endLine");
        if (endLine < startLine) {
            throw new IOException("MINOS export contains an invalid symbol line range");
        }
        String name = requiredText(node, "name");
        String qualifiedName = optionalText(node, "qualifiedName");
        if (qualifiedName == null) {
            qualifiedName = name;
        }
        String signature = optionalText(node, "signature");
        if (signature == null) {
            signature = "";
        }
        return new IndexedSymbol(
                relativePath,
                new CodeSymbol(
                        kind,
                        name,
                        qualifiedName,
                        signature,
                        startLine,
                        endLine,
                        SOURCE_PROVIDER));
    }

    private static IndexedRelation mapRelation(Path root, JsonNode node) throws IOException {
        if (!"RESOLVED".equals(optionalText(node, "resolutionStatus"))) {
            return null;
        }
        RelationKind kind = mapRelationKind(optionalText(node, "kind"));
        if (kind == null) {
            return null;
        }
        String relativePath = safeRelativePath(root, requiredText(node, "filePath"));
        if (relativePath == null) {
            return null;
        }
        String source = requiredText(node, "sourceQualifiedName");
        String target = requiredText(node, "targetQualifiedName");
        double confidence = confidence(node);
        return new IndexedRelation(
                relativePath,
                new SymbolRelation(kind, source, target, confidence, SOURCE_PROVIDER));
    }

    private static SymbolKind mapSymbolKind(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "CLASS" -> SymbolKind.CLASS;
            case "INTERFACE", "TRAIT" -> SymbolKind.INTERFACE;
            case "RECORD" -> SymbolKind.RECORD;
            case "ENUM" -> SymbolKind.ENUM;
            case "ANNOTATION" -> SymbolKind.ANNOTATION;
            case "METHOD", "FUNCTION" -> SymbolKind.METHOD;
            case "CONSTRUCTOR" -> SymbolKind.CONSTRUCTOR;
            case "STRUCT", "TYPE_ALIAS" -> SymbolKind.TYPE;
            default -> null;
        };
    }

    private static RelationKind mapRelationKind(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "IMPORTS" -> RelationKind.IMPORTS;
            case "EXTENDS" -> RelationKind.EXTENDS;
            case "IMPLEMENTS" -> RelationKind.IMPLEMENTS;
            case "CALLS" -> RelationKind.CALLS;
            case "REFERENCES" -> RelationKind.REFERENCES;
            case "TYPE_DEFINITION" -> RelationKind.TYPE_DEFINITION;
            case "DEFINITION" -> RelationKind.DEFINITION_OF;
            default -> null;
        };
    }

    private static double confidence(JsonNode node) throws IOException {
        JsonNode confidence = node.get("confidence");
        if (confidence != null && confidence.isNumber()) {
            double value = confidence.asDouble();
            if (Double.isFinite(value) && value >= 0.0d && value <= 1.0d) {
                return value;
            }
            throw new IOException("MINOS export contains an invalid relation confidence");
        }
        if ("FACTUAL".equals(optionalText(node, "nature"))) {
            return 1.0d;
        }
        throw new IOException("MINOS derived relation is missing confidence");
    }

    private static String safeRelativePath(Path root, String exportedPath) {
        try {
            Path raw = Path.of(exportedPath);
            if (raw.isAbsolute()) {
                return null;
            }
            Path normalized = raw.normalize();
            if (normalized.getNameCount() == 0 || normalized.startsWith("..")) {
                return null;
            }
            Path resolved = root.resolve(normalized).normalize();
            if (!resolved.startsWith(root) || resolved.equals(root) || !Files.isRegularFile(resolved)) {
                return null;
            }
            Path canonical = resolved.toRealPath();
            if (!canonical.startsWith(root)) {
                return null;
            }
            return normalized.toString().replace('\\', '/');
        } catch (InvalidPathException | IOException exception) {
            return null;
        }
    }

    private static Path normalizedAbsolutePath(String value, String field) throws IOException {
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute() || !path.equals(path.normalize())) {
                throw new IOException("MINOS export " + field + " must be canonical and absolute");
            }
            return path;
        } catch (InvalidPathException exception) {
            throw new IOException("MINOS export contains an invalid " + field, exception);
        }
    }

    private static JsonNode requiredObject(JsonNode parent, String field) throws IOException {
        JsonNode node = parent.get(field);
        if (node == null || !node.isObject()) {
            throw new IOException("MINOS export field '" + field + "' must be an object");
        }
        return node;
    }

    private static Iterable<JsonNode> requiredArray(JsonNode parent, String field) throws IOException {
        JsonNode node = parent.get(field);
        if (node == null || !node.isArray()) {
            throw new IOException("MINOS export field '" + field + "' must be an array");
        }
        return node;
    }

    private static void requireText(JsonNode parent, String field, String expected) throws IOException {
        String value = requiredText(parent, field);
        if (!expected.equals(value)) {
            throw new IOException(
                    "unsupported MINOS export " + field + ": " + value + " (expected " + expected + ")");
        }
    }

    private static String requiredText(JsonNode parent, String field) throws IOException {
        String value = optionalText(parent, field);
        if (value == null) {
            throw new IOException("MINOS export field '" + field + "' must not be blank");
        }
        return value;
    }

    private static String optionalText(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isBlank() ? null : value;
    }

    private static int positiveLine(JsonNode parent, String field) throws IOException {
        JsonNode node = parent.get(field);
        if (node == null || !node.canConvertToInt()) {
            throw new IOException("MINOS export field '" + field + "' must be an integer");
        }
        int value = node.asInt();
        if (value < 1) {
            throw new IOException("MINOS export field '" + field + "' must be positive");
        }
        return value;
    }

    record Configuration(Path nexusHome, Duration timeout) {
        Configuration {
            Objects.requireNonNull(nexusHome, "nexusHome");
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
                throw new IllegalArgumentException("timeout must be between 1 and 300 seconds");
            }
            Path configuredHome = nexusHome.toAbsolutePath().normalize();
            Path configuredJar = configuredHome.resolve(MINOS_CLASSPATH).normalize();
            if (!Files.isRegularFile(configuredJar)) {
                throw new IllegalArgumentException("MINOS integration JAR does not exist: " + configuredJar);
            }
            try {
                Path canonicalHome = configuredHome.toRealPath();
                Path canonicalJar = configuredJar.toRealPath();
                if (!canonicalJar.startsWith(canonicalHome)) {
                    throw new IllegalArgumentException("MINOS integration JAR must remain under NEXUS_HOME");
                }
                nexusHome = canonicalHome;
            } catch (IOException exception) {
                throw new IllegalArgumentException("MINOS integration paths cannot be canonicalized", exception);
            }
        }
    }

    @FunctionalInterface
    interface ExportRunner {
        String export(Path projectRoot) throws IOException;
    }

    private static final class ProcessExportRunner implements ExportRunner {

        private final Configuration configuration;

        private ProcessExportRunner(Configuration configuration) {
            this.configuration = Objects.requireNonNull(configuration, "configuration");
        }

        @Override
        public String export(Path projectRoot) throws IOException {
            Path stdout = Files.createTempFile("nexus-minos-", ".json");
            Path stderr = Files.createTempFile("nexus-minos-", ".err");
            try {
                ProcessBuilder builder = new ProcessBuilder(
                        JAVA_COMMAND,
                        "-cp",
                        MINOS_CLASSPATH,
                        BRIDGE_MAIN_CLASS);
                builder.directory(configuration.nexusHome().toFile());
                builder.environment().put("MINOS_HOME", MINOS_HOME);
                Process process = builder
                        .redirectOutput(stdout.toFile())
                        .redirectError(stderr.toFile())
                        .start();

                try (BufferedWriter writer = process.outputWriter(StandardCharsets.UTF_8)) {
                    writer.write(projectRoot.toString());
                    writer.newLine();
                }

                boolean completed;
                try {
                    completed = process.waitFor(configuration.timeout().toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                    throw new IOException("MINOS export interrupted", exception);
                }
                if (!completed) {
                    process.destroyForcibly();
                    throw new IOException("MINOS export timed out after " + configuration.timeout().toSeconds() + "s");
                }
                String errors = Files.readString(stderr, StandardCharsets.UTF_8);
                if (process.exitValue() != 0) {
                    throw new IOException(
                            "MINOS export failed with exit code " + process.exitValue() + ": " + diagnostic(errors));
                }
                if (Files.size(stdout) > MAX_EXPORT_BYTES) {
                    throw new IOException("MINOS export exceeds the 128 MiB transport limit");
                }
                return Files.readString(stdout, StandardCharsets.UTF_8);
            } finally {
                Files.deleteIfExists(stdout);
                Files.deleteIfExists(stderr);
            }
        }

        private static String diagnostic(String value) {
            String normalized = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
            if (normalized.isBlank()) {
                return "no diagnostic";
            }
            return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
        }
    }
}
