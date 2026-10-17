package com.nexus.index.scip;

import static com.nexus.index.scip.ScipWireInput.*;

import com.nexus.index.CodeIndexImporter;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.IndexedRelation;
import com.nexus.index.IndexedSymbol;
import com.nexus.security.ProjectPathGuard;
import com.nexus.security.SafeFileIO;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Imports the subset of SCIP needed by NEXUS without coupling the domain model
 * to generated Protobuf classes. Unknown SCIP fields are deliberately skipped.
 */
public final class ScipCodeIndexImporter implements CodeIndexImporter {

    public static final String SOURCE_PROVIDER = "scip";
    public static final String DEFAULT_INDEX_FILE = "index.scip";
    public static final int MAX_SYMBOL_FACTS = 500_000;
    public static final int MAX_RELATION_FACTS = 500_000;
    public static final int MAX_TOTAL_FACTS = MAX_SYMBOL_FACTS + MAX_RELATION_FACTS;

    static final int MAX_PARSED_DOCUMENTS = 100_000;
    static final int MAX_PARSED_OCCURRENCES = 500_000;
    static final int MAX_PARSED_SYMBOL_INFOS = 500_000;
    static final int MAX_PARSED_RELATIONSHIPS = 500_000;


    private final String indexFileName;
    private final long maxIndexBytes;
    private final int maxMessageBytes;
    private final int maxSymbolFacts;
    private final int maxRelationFacts;
    private final int maxTotalFacts;
    private final ParseLimits parseLimits;

    public ScipCodeIndexImporter() {
        this(
                DEFAULT_INDEX_FILE,
                ScipIndexLimits.maxIndexBytesFromEnvironment(),
                ScipIndexLimits.maxMessageBytesFromEnvironment());
    }

    public ScipCodeIndexImporter(String indexFileName) {
        this(
                indexFileName,
                ScipIndexLimits.maxIndexBytesFromEnvironment(),
                ScipIndexLimits.maxMessageBytesFromEnvironment());
    }

    ScipCodeIndexImporter(String indexFileName, long maxIndexBytes, int maxMessageBytes) {
        this(
                indexFileName,
                maxIndexBytes,
                maxMessageBytes,
                MAX_SYMBOL_FACTS,
                MAX_RELATION_FACTS,
                MAX_TOTAL_FACTS);
    }

    ScipCodeIndexImporter(
            String indexFileName,
            long maxIndexBytes,
            int maxMessageBytes,
            int maxSymbolFacts,
            int maxRelationFacts,
            int maxTotalFacts) {
        this(
                indexFileName,
                maxIndexBytes,
                maxMessageBytes,
                maxSymbolFacts,
                maxRelationFacts,
                maxTotalFacts,
                ParseLimits.defaults());
    }

    ScipCodeIndexImporter(
            String indexFileName,
            long maxIndexBytes,
            int maxMessageBytes,
            int maxSymbolFacts,
            int maxRelationFacts,
            int maxTotalFacts,
            ParseLimits parseLimits) {
        this.indexFileName = Objects.requireNonNull(indexFileName, "indexFileName");
        if (indexFileName.isBlank()) {
            throw new IllegalArgumentException("indexFileName ne doit pas être vide");
        }
        if (maxIndexBytes <= 0) {
            throw new IllegalArgumentException("maxIndexBytes doit être strictement positif");
        }
        if (maxMessageBytes <= 0) {
            throw new IllegalArgumentException("maxMessageBytes doit être strictement positif");
        }
        if (maxSymbolFacts <= 0) {
            throw new IllegalArgumentException("maxSymbolFacts doit être strictement positif");
        }
        if (maxRelationFacts <= 0) {
            throw new IllegalArgumentException("maxRelationFacts doit être strictement positif");
        }
        if (maxTotalFacts <= 0) {
            throw new IllegalArgumentException("maxTotalFacts doit être strictement positif");
        }
        this.maxIndexBytes = maxIndexBytes;
        this.maxMessageBytes = maxMessageBytes;
        this.maxSymbolFacts = maxSymbolFacts;
        this.maxRelationFacts = maxRelationFacts;
        this.maxTotalFacts = maxTotalFacts;
        this.parseLimits = Objects.requireNonNull(parseLimits, "parseLimits");
    }

    @Override
    public String sourceProvider() {
        return SOURCE_PROVIDER;
    }

    @Override
    public Optional<CodeIntelligenceSnapshot> importIndex(Path projectRoot) throws IOException {
        return importIndex(projectRoot, null);
    }

    @Override
    public Optional<CodeIntelligenceSnapshot> importIndex(Path projectRoot, Set<String> scannedPaths) throws IOException {
        ProjectPathGuard pathGuard = new ProjectPathGuard(projectRoot);
        Path indexCandidate = pathGuard.resolve(Path.of(indexFileName));
        Path indexFile;
        try {
            indexFile = pathGuard.requireRegularFile(indexCandidate);
        } catch (IOException missingOrUnsafeIndex) {
            if (Files.exists(indexCandidate, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(indexCandidate)) {
                throw missingOrUnsafeIndex;
            }
            return Optional.empty();
        }

        ScipSnapshotMapper snapshot = new ScipSnapshotMapper(pathGuard, scannedPaths,
                maxSymbolFacts, maxRelationFacts, maxTotalFacts);
        ScipParseBudget parseBudget = new ScipParseBudget(parseLimits);

        try (InputStream input = new BufferedInputStream(
                SafeFileIO.newInputStreamNoFollow(indexFile, maxIndexBytes))) {
            while (true) {
                long rawTag = readVarintOrEof(input);
                if (rawTag < 0) {
                    break;
                }
                int tag = checkedInt(rawTag, "tag SCIP");
                int fieldNumber = tag >>> 3;
                int wireType = tag & 0x7;
                if (fieldNumber == 2 && wireType == WireType.LENGTH_DELIMITED) {
                    // Charge the document before allocating its length-delimited byte array.
                    parseBudget.document();
                    byte[] documentPayload = readLengthDelimited(input, maxMessageBytes);
                    snapshot.importDocument(ScipDocumentParser.parseDocument(documentPayload, maxMessageBytes, parseBudget));
                } else {
                    skipField(input, wireType);
                }
            }
        }

        return Optional.of(snapshot.snapshot());
    }

    record ParseLimits(
            int maxDocuments,
            int maxOccurrences,
            int maxSymbolInfos,
            int maxRelationships) {
        ParseLimits {
            if (maxDocuments <= 0 || maxOccurrences <= 0 || maxSymbolInfos <= 0 || maxRelationships <= 0) {
                throw new IllegalArgumentException("Les limites de parsing SCIP doivent être strictement positives");
            }
        }

        static ParseLimits defaults() {
            return new ParseLimits(
                    MAX_PARSED_DOCUMENTS,
                    MAX_PARSED_OCCURRENCES,
                    MAX_PARSED_SYMBOL_INFOS,
                    MAX_PARSED_RELATIONSHIPS);
        }
    }

}
