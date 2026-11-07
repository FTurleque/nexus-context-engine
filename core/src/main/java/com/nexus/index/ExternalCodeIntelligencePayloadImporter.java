package com.nexus.index;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Set;

/**
 * Port d'import d'un export d'intelligence de code fourni explicitement par
 * l'appelant (aujourd'hui l'export JSON MINOS, ADR-0044). {@code ProjectIndexingService}
 * en dépend pour prendre les verrous d'indexation sans connaître l'adaptateur de format.
 */
public interface ExternalCodeIntelligencePayloadImporter {

    /** Convertit le payload ; les chemins sont validés contre les fichiers canoniques déjà indexés. */
    CodeIntelligenceSnapshot importPayload(Path projectRoot, Set<String> indexedProjectFiles, String payload)
            throws IOException;

    /** Lit un payload UTF-8 sous la borne de transport du format, sans fermer le flux de l'appelant. */
    String readBoundedPayload(InputStream input) throws IOException;
}
