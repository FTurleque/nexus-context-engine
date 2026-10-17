package com.nexus.index.minos;


import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.security.ProjectPathGuard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import static com.nexus.index.minos.MinosPathPolicy.*;
import static com.nexus.index.minos.MinosPayloadReader.requireTransportSize;
import static com.nexus.index.minos.MinosDocumentParser.requiredParserText;

/**
 * Adaptateur Java 21 pour l'export JSON versionné produit par MINOS.
 *
 * <p>Le chemin applicatif doit fournir la liste canonique des fichiers déjà
 * indexés par NEXUS. L'ancienne surcharge à deux arguments reste disponible pour
 * les outils/tests autonomes mais route désormais sa découverte par le scanner
 * projet borné de NEXUS.</p>
 *
 * <p>Le document est parsé en streaming : NEXUS ne matérialise jamais l'arbre
 * JSON complet en mémoire. Les faits symboles/relations sont lus et validés un
 * par un, avec des plafonds explicites en plus de la limite de transport.</p>
 */

public final class MinosCodeIndexImporter {

    public static final String SOURCE_PROVIDER = "minos";
    public static final long MAX_EXPORT_BYTES = 128L * 1024L * 1024L;
    public static final int MAX_SYMBOL_FACTS = 500_000;
    public static final int MAX_RELATION_FACTS = 500_000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Compatibilité autonome. La façade NexusApplication utilise la surcharge
     * avec fichiers canoniques. Cette surcharge reste supportée et sa découverte
     * physique respecte les limites de {@link ProjectScanner}. Les nouveaux appels
     * disposant déjà de l'index canonique peuvent utiliser la surcharge à trois arguments.
     */
    public CodeIntelligenceSnapshot importPayload(Path projectRoot, String payload) throws IOException {
        Path root = Objects.requireNonNull(projectRoot, "projectRoot").toRealPath();
        Set<String> scannedProjectFiles = new LinkedHashSet<>();
        for (var scannedFile : new ProjectScanner().scan(root)) {
            scannedProjectFiles.add(scannedFile.relativePath());
        }
        return importPayload(root, scannedProjectFiles, payload);
    }

    public CodeIntelligenceSnapshot importPayload(
            Path projectRoot,
            Set<String> indexedProjectFiles,
            String payload) throws IOException {
        Path root = Objects.requireNonNull(projectRoot, "projectRoot").toRealPath();
        ProjectPathGuard pathGuard = new ProjectPathGuard(root);
        Set<String> safeProjectFiles = canonicalIndexedFiles(indexedProjectFiles);
        String documentPayload = Objects.requireNonNull(payload, "payload");
        requireTransportSize(documentPayload);
        requireUnambiguousPathFormat(safeProjectFiles, documentPayload);

        try (JsonParser parser = objectMapper.createParser(documentPayload)) {
            return new MinosDocumentParser(objectMapper).parseDocument(root, pathGuard, safeProjectFiles, parser);
        }
    }

    /**
     * Le producteur MINOS historique v1 remplaçait les backslashes POSIX.
     * Avec de tels composants dans le corpus, aucune inférence n'est sûre.
     * Vérifier le format avant de lire le moindre fichier source, même lorsque
     * la déclaration apparaît après les faits dans le JSON.
     */
    private void requireUnambiguousPathFormat(Set<String> files, String payload) throws IOException {
        if (files.stream().noneMatch(path -> path.indexOf('\\') >= 0)) return;
        try (JsonParser parser = objectMapper.createParser(payload)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new IOException("Invalid MINOS export");
            String encoding = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) throw new IOException("Invalid MINOS export");
                String field = parser.currentName();
                JsonToken token = parser.nextToken();
                if ("repositoryPathFormat".equals(field)) {
                    encoding = requiredParserText(parser, token, field);
                } else {
                    parser.skipChildren();
                }
            }
            if (!"repository-components-v2".equals(encoding)) {
                throw new IOException("MINOS export requires repositoryPathFormat=repository-components-v2 for this corpus; regenerate with a component-preserving producer");
            }
        }
    }

    /**
     * Lit un payload UTF-8 borné sans conserver en parallèle un byte[] de la taille
     * complète du document. Cette primitive est destinée notamment à la CLI stdin.
     */
    public static String readPayload(InputStream input) throws IOException {
        return MinosPayloadReader.readPayload(input);
    }

}
