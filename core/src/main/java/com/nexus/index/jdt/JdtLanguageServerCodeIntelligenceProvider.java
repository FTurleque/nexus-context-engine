package com.nexus.index.jdt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.config.NexusPaths;
import com.nexus.index.CodeIntelligenceProvider;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.CodeSymbol;
import com.nexus.index.IndexedRelation;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.RelationKind;
import com.nexus.index.ScannedFile;
import com.nexus.index.SymbolKind;
import com.nexus.index.SymbolRelation;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.security.SafeFileIO;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final System.Logger LOGGER =
            System.getLogger(JdtLanguageServerCodeIntelligenceProvider.class.getName());
    private static final int DEFAULT_MAX_SYMBOLS = 250;
    private static final long DEFAULT_TIMEOUT_SECONDS = 120L;
    static final int MAX_SYMBOLS = 10_000;
    static final long MAX_TIMEOUT_SECONDS = 3_600L;
    static final int MAX_SNAPSHOT_SYMBOLS = 100_000;
    static final int MAX_SNAPSHOT_RELATIONS = 500_000;
    private static final double JDT_CONFIDENCE = 1.0d;
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");

    private static final int LSP_CLASS = 5;
    private static final int LSP_METHOD = 6;
    private static final int LSP_CONSTRUCTOR = 9;
    private static final int LSP_ENUM = 10;
    private static final int LSP_INTERFACE = 11;
    private static final int LSP_STRUCT = 23;

    private final Configuration configuration;
    private final SessionFactory sessionFactory;
    private final ObjectMapper objectMapper;
    private final SnapshotLimits snapshotLimits;

    public JdtLanguageServerCodeIntelligenceProvider(Configuration configuration) {
        this(configuration, StdioSession::open, new ObjectMapper(), SnapshotLimits.defaults());
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
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.snapshotLimits = Objects.requireNonNull(snapshotLimits, "snapshotLimits");
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
                    throw snapshotLimitFailure("symboles", snapshotLimits.maxSymbols());
                }

                String content = SafeFileIO.readStringNoFollow(file.absolutePath());
                String uri = file.absolutePath().toUri().toString();
                session.notify("textDocument/didOpen", didOpenParams(uri, content));
                openedUris.add(uri);
                JsonNode response = session.request("textDocument/documentSymbol", textDocumentParams(uri));
                List<ProviderSymbol> fileSymbols = parseDocumentSymbols(
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
                collectReferences(session, root, symbolsByPath, symbol, relations);
                if (supportsImplementationQuery(symbol.symbol().kind())) {
                    collectImplementations(session, root, symbolsByPath, symbol, relations);
                }
                if (isType(symbol.symbol().kind())) {
                    collectTypeHierarchy(session, root, symbolsByPath, symbol, relations);
                }
                if (isCallable(symbol.symbol().kind())) {
                    collectCallHierarchy(session, root, symbolsByPath, symbol, relations);
                }
            }

            for (String uri : openedUris) {
                session.notify("textDocument/didClose", didCloseParams(uri));
            }
        }

        return new CodeIntelligenceSnapshot(
                SOURCE_PROVIDER,
                List.copyOf(indexedSymbols),
                List.copyOf(relations.values()));
    }

    private void collectReferences(
            Session session,
            Path projectRoot,
            Map<String, List<ProviderSymbol>> symbolsByPath,
            ProviderSymbol symbol,
            Map<String, IndexedRelation> relations) throws IOException {
        ObjectNode params = positionParams(symbol);
        ObjectNode context = objectMapper.createObjectNode();
        context.put("includeDeclaration", false);
        params.set("context", context);
        JsonNode response = session.request("textDocument/references", params);
        for (JsonNode locationNode : arrayElements(response)) {
            LocationRef location = locationFrom(locationNode, projectRoot);
            if (location == null) {
                continue;
            }
            String source = referenceAt(symbolsByPath, location);
            addRelation(
                    relations,
                    location.relativePath(),
                    RelationKind.REFERENCES,
                    source,
                    symbol.symbol().qualifiedName());
        }
    }

    private void collectImplementations(
            Session session,
            Path projectRoot,
            Map<String, List<ProviderSymbol>> symbolsByPath,
            ProviderSymbol symbol,
            Map<String, IndexedRelation> relations) throws IOException {
        JsonNode response = session.request("textDocument/implementation", positionParams(symbol));
        for (JsonNode locationNode : arrayElements(response)) {
            LocationRef implementation = locationFrom(locationNode, projectRoot);
            if (implementation == null) {
                continue;
            }
            String implementationRef = referenceAt(symbolsByPath, implementation);
            addRelation(
                    relations,
                    implementation.relativePath(),
                    RelationKind.IMPLEMENTS,
                    implementationRef,
                    symbol.symbol().qualifiedName());
        }
    }

    private void collectTypeHierarchy(
            Session session,
            Path projectRoot,
            Map<String, List<ProviderSymbol>> symbolsByPath,
            ProviderSymbol symbol,
            Map<String, IndexedRelation> relations) throws IOException {
        JsonNode prepared = session.request("textDocument/prepareTypeHierarchy", positionParams(symbol));
        for (JsonNode item : arrayElements(prepared)) {
            ObjectNode itemParams = objectMapper.createObjectNode();
            itemParams.set("item", item);

            JsonNode supertypes = session.request("typeHierarchy/supertypes", itemParams);
            for (JsonNode supertype : arrayElements(supertypes)) {
                LocationRef target = hierarchyLocation(supertype, projectRoot);
                if (target == null) {
                    continue;
                }
                String targetRef = hierarchyReference(symbolsByPath, target, supertype.path("name").asText("<unknown>"));
                RelationKind kind = hierarchyKind(symbol.lspKind(), supertype.path("kind").asInt());
                addRelation(
                        relations,
                        symbol.relativePath(),
                        kind,
                        symbol.symbol().qualifiedName(),
                        targetRef);
            }

            JsonNode subtypes = session.request("typeHierarchy/subtypes", itemParams);
            for (JsonNode subtype : arrayElements(subtypes)) {
                LocationRef source = hierarchyLocation(subtype, projectRoot);
                if (source == null) {
                    continue;
                }
                String sourceRef = hierarchyReference(symbolsByPath, source, subtype.path("name").asText("<unknown>"));
                RelationKind kind = hierarchyKind(subtype.path("kind").asInt(), symbol.lspKind());
                addRelation(
                        relations,
                        source.relativePath(),
                        kind,
                        sourceRef,
                        symbol.symbol().qualifiedName());
            }
        }
    }

    private void collectCallHierarchy(
            Session session,
            Path projectRoot,
            Map<String, List<ProviderSymbol>> symbolsByPath,
            ProviderSymbol symbol,
            Map<String, IndexedRelation> relations) throws IOException {
        JsonNode prepared = session.request("textDocument/prepareCallHierarchy", positionParams(symbol));
        for (JsonNode item : arrayElements(prepared)) {
            ObjectNode itemParams = objectMapper.createObjectNode();
            itemParams.set("item", item);

            JsonNode outgoingCalls = session.request("callHierarchy/outgoingCalls", itemParams);
            for (JsonNode call : arrayElements(outgoingCalls)) {
                JsonNode to = call.path("to");
                LocationRef target = hierarchyLocation(to, projectRoot);
                if (target == null) {
                    continue;
                }
                String targetRef = hierarchyReference(symbolsByPath, target, to.path("name").asText("<unknown>"));
                addRelation(
                        relations,
                        symbol.relativePath(),
                        RelationKind.CALLS,
                        symbol.symbol().qualifiedName(),
                        targetRef);
            }

            JsonNode incomingCalls = session.request("callHierarchy/incomingCalls", itemParams);
            for (JsonNode call : arrayElements(incomingCalls)) {
                JsonNode from = call.path("from");
                LocationRef source = hierarchyLocation(from, projectRoot);
                if (source == null) {
                    continue;
                }
                String sourceRef = hierarchyReference(symbolsByPath, source, from.path("name").asText("<unknown>"));
                addRelation(
                        relations,
                        source.relativePath(),
                        RelationKind.CALLS,
                        sourceRef,
                        symbol.symbol().qualifiedName());
            }
        }
    }

    private void addRelation(
            Map<String, IndexedRelation> relations,
            String relativePath,
            RelationKind kind,
            String source,
            String target) throws IOException {
        if (source == null || target == null || source.isBlank() || target.isBlank() || source.equals(target)) {
            return;
        }
        String key = relativePath + "|" + kind + "|" + source + "|" + target;
        if (relations.containsKey(key)) {
            return;
        }
        if (relations.size() >= snapshotLimits.maxRelations()) {
            throw snapshotLimitFailure("relations", snapshotLimits.maxRelations());
        }
        SymbolRelation relation = new SymbolRelation(kind, source, target, JDT_CONFIDENCE, SOURCE_PROVIDER);
        relations.put(key, new IndexedRelation(relativePath, relation));
    }

    private static IOException snapshotLimitFailure(String kind, int maximum) {
        return new IOException("JDT LS dépasse la limite de snapshot de " + maximum + " " + kind);
    }

    private ObjectNode positionParams(ProviderSymbol symbol) {
        ObjectNode params = textDocumentParams(symbol.uri());
        ObjectNode position = objectMapper.createObjectNode();
        position.put("line", symbol.selectionLineZeroBased());
        position.put("character", symbol.selectionCharacter());
        params.set("position", position);
        return params;
    }

    private ObjectNode textDocumentParams(String uri) {
        ObjectNode params = objectMapper.createObjectNode();
        ObjectNode textDocument = objectMapper.createObjectNode();
        textDocument.put("uri", uri);
        params.set("textDocument", textDocument);
        return params;
    }

    private ObjectNode didOpenParams(String uri, String content) {
        ObjectNode params = objectMapper.createObjectNode();
        ObjectNode textDocument = objectMapper.createObjectNode();
        textDocument.put("uri", uri);
        textDocument.put("languageId", "java");
        textDocument.put("version", 1);
        textDocument.put("text", content);
        params.set("textDocument", textDocument);
        return params;
    }

    private ObjectNode didCloseParams(String uri) {
        return textDocumentParams(uri);
    }

    private List<ProviderSymbol> parseDocumentSymbols(
            JsonNode response,
            String relativePath,
            String uri,
            String packageName,
            int sourceLineCount,
            int maxSymbols) throws IOException {
        List<ProviderSymbol> symbols = new ArrayList<>();
        DocumentSymbolContext context = new DocumentSymbolContext(
                relativePath, uri, packageName, sourceLineCount, maxSymbols);
        for (JsonNode node : arrayElements(response)) {
            flattenDocumentSymbol(node, context, List.of(), symbols);
        }
        return List.copyOf(symbols);
    }

    private void flattenDocumentSymbol(
            JsonNode node,
            DocumentSymbolContext context,
            List<String> ownerTypes,
            List<ProviderSymbol> output) throws IOException {
        ParsedDocumentSymbol parsed = parseDocumentSymbol(node, context);
        SymbolRange range = parsed.range();
        boolean namedSupportedSymbol = parsed.mappedKind().isPresent() && !parsed.name().isBlank();
        boolean validRange = isValidJdtRange(
                range.startLine(),
                range.endLine(),
                range.selectionLine(),
                context.sourceLineCount());

        List<String> childOwners = namedSupportedSymbol && validRange
                ? appendDocumentSymbol(parsed, context, ownerTypes, output)
                : ownersForSkippedDocumentSymbol(parsed, context, ownerTypes, namedSupportedSymbol);

        for (JsonNode child : arrayElements(node.path("children"))) {
            flattenDocumentSymbol(child, context, childOwners, output);
        }
    }

    private static ParsedDocumentSymbol parseDocumentSymbol(
            JsonNode node,
            DocumentSymbolContext context) {
        int lspKind = node.path("kind").asInt(-1);
        String name = node.path("name").asText("");
        String detail = node.path("detail").asText("");
        JsonNode rangeNode = node.has("range") ? node.path("range") : node.path("location").path("range");
        JsonNode selectionRangeNode = node.has("selectionRange") ? node.path("selectionRange") : rangeNode;
        String uri = node.path("location").path("uri").asText(context.fallbackUri());
        int startLine = rangeNode.path("start").path("line").asInt(-1);
        int endLine = rangeNode.path("end").path("line").asInt(startLine);
        int selectionLine = selectionRangeNode.path("start").path("line").asInt(startLine);
        int selectionCharacter = selectionRangeNode.path("start").path("character").asInt(0);
        return new ParsedDocumentSymbol(
                lspKind,
                name,
                detail,
                uri,
                new SymbolRange(startLine, endLine, selectionLine, selectionCharacter),
                symbolKind(lspKind));
    }

    private List<String> appendDocumentSymbol(
            ParsedDocumentSymbol parsed,
            DocumentSymbolContext context,
            List<String> ownerTypes,
            List<ProviderSymbol> output) throws IOException {
        if (output.size() >= context.maxSymbols()) {
            throw snapshotLimitFailure("symboles", snapshotLimits.maxSymbols());
        }

        SymbolKind kind = parsed.mappedKind().orElseThrow();
        String signature = parsed.detail().isBlank()
                ? parsed.name()
                : parsed.name() + " " + parsed.detail().trim();
        List<String> childOwners = ownerTypes;
        String qualifiedName;
        if (isType(kind)) {
            List<String> typeNames = appendOwner(ownerTypes, parsed.name());
            qualifiedName = qualifiedOwner(context.packageName(), typeNames);
            childOwners = typeNames;
        } else {
            qualifiedName = qualifiedOwner(context.packageName(), ownerTypes) + "#" + signature;
        }

        SymbolRange range = parsed.range();
        CodeSymbol symbol = new CodeSymbol(
                kind,
                parsed.name(),
                qualifiedName,
                signature,
                range.startLine() + 1,
                range.endLine() + 1,
                SOURCE_PROVIDER);
        output.add(new ProviderSymbol(
                context.relativePath(),
                parsed.uri(),
                symbol,
                parsed.lspKind(),
                range.startLine(),
                range.endLine(),
                range.selectionLine(),
                Math.max(0, range.selectionCharacter())));
        return childOwners;
    }

    private static List<String> ownersForSkippedDocumentSymbol(
            ParsedDocumentSymbol parsed,
            DocumentSymbolContext context,
            List<String> ownerTypes,
            boolean namedSupportedSymbol) {
        SymbolRange range = parsed.range();
        if (namedSupportedSymbol) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Ignoring inconsistent JDT symbol range for {0} in {1}: start={2}, end={3}, selection={4}, lines={5}",
                    parsed.name(),
                    context.relativePath(),
                    range.startLine(),
                    range.endLine(),
                    range.selectionLine(),
                    context.sourceLineCount());
        }
        if (isLspType(parsed.lspKind()) && !parsed.name().isBlank()) {
            return appendOwner(ownerTypes, parsed.name());
        }
        return ownerTypes;
    }

    private static List<String> appendOwner(List<String> ownerTypes, String name) {
        List<String> typeNames = new ArrayList<>(ownerTypes);
        typeNames.add(name);
        return List.copyOf(typeNames);
    }

    private record SymbolRange(
            int startLine,
            int endLine,
            int selectionLine,
            int selectionCharacter) {
    }

    private record ParsedDocumentSymbol(
            int lspKind,
            String name,
            String detail,
            String uri,
            SymbolRange range,
            Optional<SymbolKind> mappedKind) {
    }

    private record DocumentSymbolContext(
            String relativePath,
            String fallbackUri,
            String packageName,
            int sourceLineCount,
            int maxSymbols) {
    }

    private static boolean isValidJdtRange(
            int startLine,
            int endLine,
            int selectionLine,
            int sourceLineCount) {
        return sourceLineCount > 0
                && startLine >= 0
                && endLine >= startLine
                && endLine < sourceLineCount
                && selectionLine >= startLine
                && selectionLine <= endLine;
    }

    private static int countSourceLines(String content) {
        long lineCount = content.lines().count();
        return lineCount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) lineCount;
    }

    private static Optional<SymbolKind> symbolKind(int lspKind) {
        return switch (lspKind) {
            case LSP_CLASS -> Optional.of(SymbolKind.CLASS);
            case LSP_INTERFACE -> Optional.of(SymbolKind.INTERFACE);
            case LSP_STRUCT -> Optional.of(SymbolKind.RECORD);
            case LSP_ENUM -> Optional.of(SymbolKind.ENUM);
            case LSP_METHOD -> Optional.of(SymbolKind.METHOD);
            case LSP_CONSTRUCTOR -> Optional.of(SymbolKind.CONSTRUCTOR);
            default -> Optional.empty();
        };
    }

    private static boolean isLspType(int lspKind) {
        return lspKind == LSP_CLASS || lspKind == LSP_INTERFACE || lspKind == LSP_STRUCT || lspKind == LSP_ENUM;
    }

    private static boolean isType(SymbolKind kind) {
        return switch (kind) {
            case CLASS, INTERFACE, RECORD, ENUM, ANNOTATION, TYPE -> true;
            case METHOD, CONSTRUCTOR -> false;
        };
    }

    private static boolean isCallable(SymbolKind kind) {
        return kind == SymbolKind.METHOD || kind == SymbolKind.CONSTRUCTOR;
    }

    private static boolean supportsImplementationQuery(SymbolKind kind) {
        return isType(kind) || kind == SymbolKind.METHOD;
    }

    private static RelationKind hierarchyKind(int subtypeKind, int supertypeKind) {
        if (supertypeKind == LSP_INTERFACE && subtypeKind != LSP_INTERFACE) {
            return RelationKind.IMPLEMENTS;
        }
        return RelationKind.EXTENDS;
    }

    private static String qualifiedOwner(String packageName, List<String> ownerTypes) {
        String owner = ownerTypes.isEmpty() ? "<unknown>" : String.join(".", ownerTypes);
        return packageName.isBlank() ? owner : packageName + "." + owner;
    }

    private static String packageName(String content) {
        Matcher matcher = PACKAGE_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static List<JsonNode> arrayElements(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (node.isArray()) {
            List<JsonNode> elements = new ArrayList<>();
            node.forEach(elements::add);
            return List.copyOf(elements);
        }
        return List.of(node);
    }

    private static LocationRef locationFrom(JsonNode node, Path projectRoot) {
        String uri = node.path("uri").asText("");
        JsonNode range = node.path("range");
        if (uri.isBlank()) {
            uri = node.path("targetUri").asText("");
            range = node.has("targetSelectionRange") ? node.path("targetSelectionRange") : node.path("targetRange");
        }
        return locationFrom(uri, range, projectRoot);
    }

    private static LocationRef hierarchyLocation(JsonNode node, Path projectRoot) {
        return locationFrom(node.path("uri").asText(""), node.path("selectionRange"), projectRoot);
    }

    private static LocationRef locationFrom(String uri, JsonNode range, Path projectRoot) {
        if (uri.isBlank() || range == null || range.isMissingNode()) {
            return null;
        }
        try {
            URI parsedUri = URI.create(uri);
            if (!"file".equalsIgnoreCase(parsedUri.getScheme())) {
                return null;
            }
            Path absolutePath = Path.of(parsedUri).toAbsolutePath().normalize();
            if (!absolutePath.startsWith(projectRoot)) {
                return null;
            }
            String relativePath = projectRoot.relativize(absolutePath).toString().replace('\\', '/');
            int line = range.path("start").path("line").asInt(-1);
            int character = range.path("start").path("character").asInt(0);
            if (line < 0) {
                return null;
            }
            return new LocationRef(relativePath, line, character);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String hierarchyReference(
            Map<String, List<ProviderSymbol>> symbolsByPath,
            LocationRef location,
            String fallbackName) {
        String resolved = matchingSymbol(symbolsByPath, location)
                .map(symbol -> symbol.symbol().qualifiedName())
                .orElse(null);
        return resolved == null
                ? location.relativePath() + "#" + fallbackName
                : resolved;
    }

    private static String referenceAt(
            Map<String, List<ProviderSymbol>> symbolsByPath,
            LocationRef location) {
        return matchingSymbol(symbolsByPath, location)
                .map(symbol -> symbol.symbol().qualifiedName())
                .orElse(location.relativePath() + ":" + (location.lineZeroBased() + 1));
    }

    private static Optional<ProviderSymbol> matchingSymbol(
            Map<String, List<ProviderSymbol>> symbolsByPath,
            LocationRef location) {
        return symbolsByPath.getOrDefault(location.relativePath(), List.of()).stream()
                .filter(symbol -> location.lineZeroBased() >= symbol.startLineZeroBased())
                .filter(symbol -> location.lineZeroBased() <= symbol.endLineZeroBased())
                .min(Comparator.comparingInt(symbol -> symbol.endLineZeroBased() - symbol.startLineZeroBased()));
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
                return files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().startsWith("org.eclipse.equinox.launcher_"))
                        .filter(path -> path.getFileName().toString().endsWith(".jar"))
                        .max(Comparator.comparing(path -> path.getFileName().toString()))
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

    private record ProviderSymbol(
            String relativePath,
            String uri,
            CodeSymbol symbol,
            int lspKind,
            int startLineZeroBased,
            int endLineZeroBased,
            int selectionLineZeroBased,
            int selectionCharacter) {
    }

    private record LocationRef(String relativePath, int lineZeroBased, int character) {
    }

    private static final class StdioSession implements Session {

        private static final String JSON_RPC_VERSION = "2.0";
        private static final int STDERR_TAIL_SIZE = 50;
        private static final int MAX_STDERR_LINE_CHARS = 4 * 1024;

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

        private StdioSession(
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

        static StdioSession open(Configuration configuration, Path projectRoot) throws IOException {
            Path launcher = configuration.launcherJar();
            Path platformConfiguration = configuration.platformConfigurationDirectory();
            Path workspace = configuration.workspaceFor(projectRoot);
            List<String> command = new ArrayList<>();
            command.add(configuration.javaCommand());
            command.add("-Declipse.application=org.eclipse.jdt.ls.core.id1");
            command.add("-Dosgi.bundles.defaultStartLevel=4");
            command.add("-Declipse.product=org.eclipse.jdt.ls.core.product");
            command.add("-Dlog.level=ERROR");
            command.add("-Xmx1G");
            command.add("--add-modules=ALL-SYSTEM");
            command.add("--add-opens=java.base/java.util=ALL-UNNAMED");
            command.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
            command.add("-jar");
            command.add(launcher.toString());
            command.add("-configuration");
            command.add(platformConfiguration.toString());
            command.add("-data");
            command.add(workspace.toString());

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(projectRoot.toFile());
            builder.environment().remove("CLIENT_PORT");
            builder.environment().remove("CLIENT_HOST");
            return new StdioSession(configuration, projectRoot, builder.start());
        }

        @Override
        public void initialize() throws IOException {
            ObjectNode params = mapper.createObjectNode();
            params.put("processId", ProcessHandle.current().pid());
            params.put("rootUri", projectRoot.toUri().toString());
            params.put("rootPath", projectRoot.toString());

            ObjectNode capabilities = mapper.createObjectNode();
            ObjectNode workspace = mapper.createObjectNode();
            workspace.put("configuration", true);
            workspace.put("workspaceFolders", true);
            capabilities.set("workspace", workspace);
            ObjectNode textDocument = mapper.createObjectNode();
            ObjectNode documentSymbol = mapper.createObjectNode();
            documentSymbol.put("hierarchicalDocumentSymbolSupport", true);
            textDocument.set("documentSymbol", documentSymbol);
            textDocument.set("references", mapper.createObjectNode());
            textDocument.set("implementation", mapper.createObjectNode());
            textDocument.set("typeHierarchy", mapper.createObjectNode());
            textDocument.set("callHierarchy", mapper.createObjectNode());
            capabilities.set("textDocument", textDocument);
            params.set("capabilities", capabilities);

            ArrayNode workspaceFolders = mapper.createArrayNode();
            ObjectNode workspaceFolder = mapper.createObjectNode();
            workspaceFolder.put("uri", projectRoot.toUri().toString());
            workspaceFolder.put("name", projectRoot.getFileName() == null ? "project" : projectRoot.getFileName().toString());
            workspaceFolders.add(workspaceFolder);
            params.set("workspaceFolders", workspaceFolders);

            ObjectNode initializationOptions = mapper.createObjectNode();
            initializationOptions.set("settings", mapper.createObjectNode());
            params.set("initializationOptions", initializationOptions);

            request("initialize", params);
            notify("initialized", mapper.createObjectNode());
            ObjectNode configurationChanged = mapper.createObjectNode();
            configurationChanged.set("settings", mapper.createObjectNode());
            notify("workspace/didChangeConfiguration", configurationChanged);
            awaitServiceReady();
        }

        @Override
        public JsonNode request(String method, JsonNode params) throws IOException {
            long id = nextRequestId.getAndIncrement();
            ObjectNode message = mapper.createObjectNode();
            message.put("jsonrpc", JSON_RPC_VERSION);
            message.put("id", id);
            message.put("method", method);
            if (params != null) {
                message.set("params", params);
            }
            writeMessage(message);
            return awaitResponse(id);
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
                if (process.isAlive()) {
                    try {
                        request("shutdown", NullNode.instance);
                    } catch (IOException ignored) {
                        // Le processus peut déjà être en train de se terminer.
                    }
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

        private JsonNode awaitResponse(long expectedId) throws IOException {
            long deadline = System.nanoTime() + configuration.timeout().toNanos();
            while (true) {
                JsonNode message = nextMessage(deadline);
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
            long deadline = System.nanoTime() + configuration.timeout().toNanos();
            while (!serviceReady) {
                JsonNode message = nextMessage(deadline);
                observeNotification(message);
                if (isServerRequest(message)) {
                    respondToServerRequest(message);
                }
            }
        }

        private JsonNode nextMessage(long deadline) throws IOException {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                throw timeoutFailure();
            }
            try {
                Inbound inbound = inbox.poll(remainingNanos, TimeUnit.NANOSECONDS);
                if (inbound == null) {
                    throw timeoutFailure();
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

        private IOException timeoutFailure() {
            return new IOException(
                    "Délai JDT LS dépassé après " + configuration.timeout().toSeconds() + " s. " + stderrSummary());
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
}
