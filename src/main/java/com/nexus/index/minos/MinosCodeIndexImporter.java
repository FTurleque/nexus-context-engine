package com.nexus.index.minos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.index.CodeIndexImporter;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.CodeSymbol;
import com.nexus.index.IndexedRelation;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.RelationKind;
import com.nexus.index.SymbolKind;
import com.nexus.index.SymbolRelation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
 * <p>No MINOS class is linked into NEXUS. A configured Java 24 runtime launches the
 * MINOS shaded JAR in a local process and NEXUS maps the returned facts into its own
 * code-intelligence model. Ranking and context selection remain entirely in NEXUS.</p>
 */
public final class MinosCodeIndexImporter implements CodeIndexImporter {

    public static final String SOURCE_PROVIDER = "minos";
    public static final String JAR_ENVIRONMENT_VARIABLE = "NEXUS_MINOS_JAR";
    public static final String JAVA_ENVIRONMENT_VARIABLE = "NEXUS_MINOS_JAVA";
    public static final String HOME_ENVIRONMENT_VARIABLE = "NEXUS_MINOS_HOME";
    public static final String TIMEOUT_ENVIRONMENT_VARIABLE = "NEXUS_MINOS_TIMEOUT_SECONDS";

    private static final String CONTRACT_VERSION = "1";
    private static final String PRODUCER = "MINOS";
    private static final long DEFAULT_TIMEOUT_SECONDS = 20L;
    private static final long MAX_TIMEOUT_SECONDS = 300L;

    private final Configuration configuration;
    private final ExportRunner exportRunner;
    private final ObjectMapper objectMapper;

    /** Creates a disabled importer used to purge stale MINOS data when integration is off. */
    public MinosCodeIndexImporter() {
        this(null, null, new ObjectMapper());
    }

    public MinosCodeIndexImporter(Configuration configuration) {
        this(
                Objects.requireNonNull(configuration, "configuration"),
                new ProcessExportRunner(configuration),
                new ObjectMapper());
    }

    MinosCodeIndexImporter(Configuration configuration, ExportRunner exportRunner, ObjectMapper objectMapper) {
        this.configuration = configuration;
        this.exportRunner = exportRunner;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        if (configuration != null) {
            Objects.requireNonNull(exportRunner, "exportRunner");
        }
    }

    public static MinosCodeIndexImporter fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    static MinosCodeIndexImporter fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String jar = trimmed(environment.get(JAR_ENVIRONMENT_VARIABLE));
        if (jar == null) {
            return new MinosCodeIndexImporter();
        }
        String javaCommand = trimmed(environment.get(JAVA_ENVIRONMENT_VARIABLE));
        if (javaCommand == null) {
            throw new IllegalArgumentException(
                    JAVA_ENVIRONMENT_VARIABLE + " is required when " + JAR_ENVIRONMENT_VARIABLE
                            + " is configured because MINOS requires Java 24");
        }
        String home = trimmed(environment.get(HOME_ENVIRONMENT_VARIABLE));
        long timeoutSeconds = positiveLong(
                environment.get(TIMEOUT_ENVIRONMENT_VARIABLE),
                DEFAULT_TIMEOUT_SECONDS,
                TIMEOUT_ENVIRONMENT_VARIABLE);
        return new MinosCodeIndexImporter(new Configuration(
                Path.of(jar),
                home == null ? null : Path.of(home),
                javaCommand,
                Duration.ofSeconds(timeoutSeconds)));
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
        String exportedRootText = requiredText(project, "rootPath");
        Path exportedRoot;
        try {
            exportedRoot = Path.of(exportedRootText).toRealPath();
        } catch (InvalidPathException exception) {
            throw new IOException("MINOS export contains an invalid project root", exception);
        }
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
            Path resolved = raw.isAbsolute() ? raw.normalize() : root.resolve(raw).normalize();
            if (!resolved.startsWith(root) || resolved.equals(root) || !Files.isRegularFile(resolved)) {
                return null;
            }
            Path canonical = resolved.toRealPath();
            if (!canonical.startsWith(root)) {
                return null;
            }
            return root.relativize(resolved).toString().replace('\\', '/');
        } catch (InvalidPathException | IOException exception) {
            return null;
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

    private static long positiveLong(String raw, long defaultValue, String name) {
        String value = trimmed(raw);
        if (value == null) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1 || parsed > MAX_TIMEOUT_SECONDS) {
                throw new IllegalArgumentException(name + " must be between 1 and " + MAX_TIMEOUT_SECONDS);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid " + name + ": " + value, exception);
        }
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    public record Configuration(
            Path minosJar,
            Path minosHome,
            String javaCommand,
            Duration timeout
    ) {
        public Configuration {
            Objects.requireNonNull(minosJar, "minosJar");
            Objects.requireNonNull(javaCommand, "javaCommand");
            Objects.requireNonNull(timeout, "timeout");
            minosJar = minosJar.toAbsolutePath().normalize();
            minosHome = minosHome == null ? null : minosHome.toAbsolutePath().normalize();
            if (!Files.isRegularFile(minosJar)) {
                throw new IllegalArgumentException("MINOS JAR does not exist: " + minosJar);
            }
            if (javaCommand.isBlank()) {
                throw new IllegalArgumentException("javaCommand must not be blank");
            }
            if (timeout.isZero() || timeout.isNegative()
                    || timeout.compareTo(Duration.ofSeconds(MAX_TIMEOUT_SECONDS)) > 0) {
                throw new IllegalArgumentException("timeout must be between 1 and " + MAX_TIMEOUT_SECONDS + " seconds");
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
            this.configuration = configuration;
        }

        @Override
        public String export(Path projectRoot) throws IOException {
            Path stdout = Files.createTempFile("nexus-minos-", ".json");
            Path stderr = Files.createTempFile("nexus-minos-", ".err");
            try {
                List<String> command = new ArrayList<>();
                command.add(configuration.javaCommand());
                if (configuration.minosHome() != null) {
                    command.add("-Dminos.home=" + configuration.minosHome());
                }
                command.add("-jar");
                command.add(configuration.minosJar().toString());
                command.add("nexus-export");
                command.add("--root");
                command.add(projectRoot.toString());

                Process process = new ProcessBuilder(command)
                        .redirectOutput(stdout.toFile())
                        .redirectError(stderr.toFile())
                        .start();
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
