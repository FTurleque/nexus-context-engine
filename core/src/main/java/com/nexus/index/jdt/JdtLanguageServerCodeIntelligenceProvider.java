package com.nexus.index.jdt;

import com.nexus.index.jdt.JdtSymbolMapper.ProviderSymbol;
import static com.nexus.index.jdt.JdtSymbolMapper.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.config.NexusPaths;
import com.nexus.index.CodeIntelligenceProvider;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.IndexedRelation;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.ScannedFile;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.security.SafeFileIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Provider Java profond basé sur un processus Eclipse JDT Language Server externe.
 *
 * <p>Le provider reste volontairement opt-in. Sa présence dépend de
 * {@code NEXUS_JDTLS_HOME} et son exécution n'est déclenchée que par une
 * indexation profonde explicite.</p>
 */
public final class JdtLanguageServerCodeIntelligenceProvider implements CodeIntelligenceProvider {

    public static final String SOURCE_PROVIDER = "jdtls";
    public static final String HOME_ENVIRONMENT_VARIABLE = "NEXUS_JDTLS_HOME";
    public static final String JAVA_ENVIRONMENT_VARIABLE = "NEXUS_JDTLS_JAVA";
    public static final String TIMEOUT_ENVIRONMENT_VARIABLE = "NEXUS_JDTLS_TIMEOUT_SECONDS";
    public static final String MAX_SYMBOLS_ENVIRONMENT_VARIABLE = "NEXUS_JDTLS_MAX_SYMBOLS";

    private static final int DEFAULT_MAX_SYMBOLS = 250;
    private static final long DEFAULT_TIMEOUT_SECONDS = 120L;
    static final int MAX_SYMBOLS = 10_000;
    static final long MAX_TIMEOUT_SECONDS = 3_600L;
    static final int MAX_SNAPSHOT_SYMBOLS = 100_000;
    static final int MAX_SNAPSHOT_RELATIONS = 500_000;
    private final Configuration configuration;
    private final SessionFactory sessionFactory;
    private final SnapshotLimits snapshotLimits;
    private final JdtSymbolMapper symbolMapper;
    private final JdtDocumentMessages messages;
    private final JdtRelationCollector relationCollector;

    public JdtLanguageServerCodeIntelligenceProvider(Configuration configuration) {
        this(configuration, JdtStdioSession::open, new ObjectMapper(), SnapshotLimits.defaults());
    }

    JdtLanguageServerCodeIntelligenceProvider(
            Configuration configuration,
            SessionFactory sessionFactory,
            ObjectMapper objectMapper) {
        this(configuration, sessionFactory, objectMapper, SnapshotLimits.defaults());
    }

    JdtLanguageServerCodeIntelligenceProvider(
            Configuration configuration,
            SessionFactory sessionFactory,
            ObjectMapper objectMapper,
            SnapshotLimits snapshotLimits) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        Objects.requireNonNull(objectMapper, "objectMapper");
        this.snapshotLimits = Objects.requireNonNull(snapshotLimits, "snapshotLimits");
        this.symbolMapper = new JdtSymbolMapper(snapshotLimits);
        this.messages = new JdtDocumentMessages(objectMapper);
        this.relationCollector = new JdtRelationCollector(objectMapper, snapshotLimits);
    }

    public static Optional<JdtLanguageServerCodeIntelligenceProvider> fromEnvironment(NexusPaths paths) {
        Objects.requireNonNull(paths, "paths");
        String configuredHome = System.getenv(HOME_ENVIRONMENT_VARIABLE);
        if (configuredHome == null || configuredHome.isBlank()) {
            return Optional.empty();
        }
        String javaCommand = environmentOrDefault(JAVA_ENVIRONMENT_VARIABLE, "java");
        long timeoutSeconds = boundedPositiveLongEnvironment(
                TIMEOUT_ENVIRONMENT_VARIABLE, DEFAULT_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
        int maxSymbols = boundedPositiveIntEnvironment(
                MAX_SYMBOLS_ENVIRONMENT_VARIABLE, DEFAULT_MAX_SYMBOLS, MAX_SYMBOLS);
        Configuration configuration = new Configuration(
                Path.of(configuredHome),
                paths.home().resolve("jdtls-workspaces"),
                javaCommand,
                Duration.ofSeconds(timeoutSeconds),
                maxSymbols);
        return Optional.of(new JdtLanguageServerCodeIntelligenceProvider(configuration));
    }

    @Override
    public String sourceProvider() {
        return SOURCE_PROVIDER;
    }

    @Override
    public CodeIntelligenceSnapshot analyze(Path projectRoot) throws IOException {
        Path root = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        // Resolve to the real path so file URIs built by the scanner (via requireRegularFile)
        // and URIs returned by the JDT session share the same canonical base for containment
        // checks. On Windows, java.io.tmpdir may carry 8.3 short names that cause startsWith()
        // to fail even for valid intra-project paths.
        try {
            root = root.toRealPath();
        } catch (IOException ignored) {
            // Project root does not exist yet; ProjectScanner will report a clear error.
        }
        List<ScannedFile> javaFiles = new ProjectScanner().scan(root).stream()
                .filter(file -> "java".equalsIgnoreCase(file.language()))
                .filter(file -> file.relativePath().toLowerCase(Locale.ROOT).endsWith(".java"))
                .sorted(Comparator.comparing(ScannedFile::relativePath))
                .toList();
        if (javaFiles.isEmpty()) {
            return CodeIntelligenceSnapshot.empty(SOURCE_PROVIDER);
        }

        Map<String, List<ProviderSymbol>> symbolsByPath = new LinkedHashMap<>();
        List<IndexedSymbol> indexedSymbols = new ArrayList<>();
        LinkedHashMap<String, IndexedRelation> relations = new LinkedHashMap<>();
        Set<String> openedUris = new LinkedHashSet<>();

        try (Session session = sessionFactory.open(configuration, root)) {
            session.initialize();
            for (ScannedFile file : javaFiles) {
                int remainingSymbols = snapshotLimits.maxSymbols() - indexedSymbols.size();
                if (remainingSymbols <= 0) {
                    throw new IOException("JDT LS dépasse la limite de snapshot de " + snapshotLimits.maxSymbols() + " symboles");
                }

                String content = SafeFileIO.readStringNoFollow(file.absolutePath());
                String uri = file.absolutePath().toUri().toString();
                session.notify("textDocument/didOpen", messages.didOpenParams(uri, content));
                openedUris.add(uri);
                JsonNode response = session.request("textDocument/documentSymbol", messages.textDocumentParams(uri));
                List<ProviderSymbol> fileSymbols = symbolMapper.parseDocumentSymbols(
                        response,
                        file.relativePath(),
                        uri,
                        packageName(content),
                        countSourceLines(content),
                        remainingSymbols);
                symbolsByPath.put(file.relativePath(), fileSymbols);
                fileSymbols.stream()
                        .map(symbol -> new IndexedSymbol(symbol.relativePath(), symbol.symbol()))
                        .forEach(indexedSymbols::add);
            }

            List<ProviderSymbol> querySymbols = symbolsByPath.values().stream()
                    .flatMap(List::stream)
                    .sorted(Comparator.comparing(ProviderSymbol::relativePath)
                            .thenComparingInt(ProviderSymbol::startLineZeroBased)
                            .thenComparing(symbol -> symbol.symbol().qualifiedName()))
                    .limit(configuration.maxSymbols())
                    .toList();

            for (ProviderSymbol symbol : querySymbols) {
                relationCollector.collectReferences(session, root, symbolsByPath, symbol, relations);
                if (supportsImplementationQuery(symbol.symbol().kind())) {
                    relationCollector.collectImplementations(session, root, symbolsByPath, symbol, relations);
                }
                if (isType(symbol.symbol().kind())) {
                    relationCollector.collectTypeHierarchy(session, root, symbolsByPath, symbol, relations);
                }
                if (isCallable(symbol.symbol().kind())) {
                    relationCollector.collectCallHierarchy(session, root, symbolsByPath, symbol, relations);
                }
            }

            for (String uri : openedUris) {
                session.notify("textDocument/didClose", messages.didCloseParams(uri));
            }
        }

        return new CodeIntelligenceSnapshot(
                SOURCE_PROVIDER,
                List.copyOf(indexedSymbols),
                List.copyOf(relations.values()));
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int boundedPositiveIntEnvironment(String name, int defaultValue, int maximum) {
        return parseBoundedPositiveInt(name, System.getenv(name), defaultValue, maximum);
    }

    private static long boundedPositiveLongEnvironment(String name, long defaultValue, long maximum) {
        return parseBoundedPositiveLong(name, System.getenv(name), defaultValue, maximum);
    }

    static int parseBoundedPositiveInt(String name, String rawValue, int defaultValue, int maximum) {
        long parsed = parseBoundedPositiveLong(name, rawValue, defaultValue, maximum);
        if (parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " dépasse la capacité entière supportée: " + parsed);
        }
        return (int) parsed;
    }

    static long parseBoundedPositiveLong(String name, String rawValue, long defaultValue, long maximum) {
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        final long parsed;
        try {
            parsed = Long.parseLong(rawValue.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " doit être un entier compris entre 1 et " + maximum, invalid);
        }
        if (parsed <= 0 || parsed > maximum) {
            throw new IllegalArgumentException(
                    name + " doit être compris entre 1 et " + maximum + " (reçu " + parsed + ")");
        }
        return parsed;
    }

    record SnapshotLimits(int maxSymbols, int maxRelations) {
        SnapshotLimits {
            if (maxSymbols <= 0 || maxSymbols > MAX_SNAPSHOT_SYMBOLS) {
                throw new IllegalArgumentException(
                        "maxSymbols snapshot doit être compris entre 1 et " + MAX_SNAPSHOT_SYMBOLS);
            }
            if (maxRelations <= 0 || maxRelations > MAX_SNAPSHOT_RELATIONS) {
                throw new IllegalArgumentException(
                        "maxRelations snapshot doit être compris entre 1 et " + MAX_SNAPSHOT_RELATIONS);
            }
        }

        static SnapshotLimits defaults() {
            return new SnapshotLimits(MAX_SNAPSHOT_SYMBOLS, MAX_SNAPSHOT_RELATIONS);
        }
    }

    public record Configuration(
            Path installationDirectory,
            Path workspaceRoot,
            String javaCommand,
            Duration timeout,
            int maxSymbols) {

        public Configuration {
            Objects.requireNonNull(installationDirectory, "installationDirectory");
            Objects.requireNonNull(workspaceRoot, "workspaceRoot");
            Objects.requireNonNull(javaCommand, "javaCommand");
            Objects.requireNonNull(timeout, "timeout");
            installationDirectory = installationDirectory.toAbsolutePath().normalize();
            workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
            if (javaCommand.isBlank()) {
                throw new IllegalArgumentException("javaCommand ne doit pas être vide");
            }
            if (timeout.isZero() || timeout.isNegative()
                    || timeout.compareTo(Duration.ofSeconds(MAX_TIMEOUT_SECONDS)) > 0) {
                throw new IllegalArgumentException(
                        "timeout doit être compris entre 1 seconde et " + MAX_TIMEOUT_SECONDS + " secondes");
            }
            if (maxSymbols <= 0 || maxSymbols > MAX_SYMBOLS) {
                throw new IllegalArgumentException(
                        "maxSymbols doit être compris entre 1 et " + MAX_SYMBOLS);
            }
        }

        Path launcherJar() throws IOException {
            Path plugins = installationDirectory.resolve("plugins");
            if (!Files.isDirectory(plugins)) {
                throw new IOException("Répertoire plugins JDT LS introuvable : " + plugins);
            }
            try (var files = Files.list(plugins)) {
                return EquinoxLauncherSelector.latest(files)
                        .orElseThrow(() -> new IOException("Launcher Equinox JDT LS introuvable dans " + plugins));
            }
        }

        Path platformConfigurationDirectory() throws IOException {
            String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String directoryName;
            if (osName.contains("win")) {
                directoryName = "config_win";
            } else if (osName.contains("mac")) {
                directoryName = "config_mac";
            } else {
                directoryName = "config_linux";
            }
            Path directory = installationDirectory.resolve(directoryName);
            if (!Files.isDirectory(directory) && !osName.contains("win") && !osName.contains("mac")) {
                Path unixFallback = installationDirectory.resolve("config_unix");
                if (Files.isDirectory(unixFallback)) {
                    return unixFallback;
                }
            }
            if (!Files.isDirectory(directory)) {
                throw new IOException("Configuration JDT LS introuvable : " + directory);
            }
            return directory;
        }

        Path workspaceFor(Path projectRoot) throws IOException {
            Path storageHome = workspaceRoot.getParent();
            if (storageHome == null) {
                throw new IOException("Racine de workspaces JDT LS sans parent : " + workspaceRoot);
            }
            NexusPaths storagePaths = new NexusPaths(storageHome);
            storagePaths.ensurePrivateDirectory(workspaceRoot);
            String id = UUID.nameUUIDFromBytes(
                    projectRoot.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8)).toString();
            Path workspace = workspaceRoot.resolve(id);
            storagePaths.ensurePrivateDirectory(workspace);
            return workspace;
        }
    }

    @FunctionalInterface
    interface SessionFactory {
        Session open(Configuration configuration, Path projectRoot) throws IOException;
    }

    interface Session extends AutoCloseable {
        void initialize() throws IOException;

        JsonNode request(String method, JsonNode params) throws IOException;

        void notify(String method, JsonNode params) throws IOException;

        @Override
        void close();
    }

}
