package com.nexus.index.jdt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.index.IndexedRelation;
import com.nexus.index.RelationKind;
import com.nexus.index.SymbolRelation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.nexus.index.jdt.JdtLanguageServerCodeIntelligenceProvider.Session;
import com.nexus.index.jdt.JdtLanguageServerCodeIntelligenceProvider.SnapshotLimits;
import static com.nexus.index.jdt.JdtLanguageServerCodeIntelligenceProvider.SOURCE_PROVIDER;
import com.nexus.index.jdt.JdtSymbolMapper.LocationRef;
import com.nexus.index.jdt.JdtSymbolMapper.ProviderSymbol;
import static com.nexus.index.jdt.JdtSymbolMapper.*;
import static com.nexus.index.jdt.JdtLocationMapper.*;

/** Composant interne du provider JDT LS. */
final class JdtRelationCollector {
    private static final double JDT_CONFIDENCE = 1.0d;
    private final ObjectMapper objectMapper;
    private final SnapshotLimits snapshotLimits;
    private final JdtDocumentMessages messages;
    JdtRelationCollector(ObjectMapper mapper, SnapshotLimits limits) {
        this.objectMapper = mapper; this.snapshotLimits = limits; this.messages = new JdtDocumentMessages(mapper);
    }
    void collectReferences(
            Session session,
            Path projectRoot,
            Map<String, List<ProviderSymbol>> symbolsByPath,
            ProviderSymbol symbol,
            Map<String, IndexedRelation> relations) throws IOException {
        ObjectNode params = messages.positionParams(symbol);
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

    void collectImplementations(
            Session session,
            Path projectRoot,
            Map<String, List<ProviderSymbol>> symbolsByPath,
            ProviderSymbol symbol,
            Map<String, IndexedRelation> relations) throws IOException {
        JsonNode response = session.request("textDocument/implementation", messages.positionParams(symbol));
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

    void collectTypeHierarchy(
            Session session,
            Path projectRoot,
            Map<String, List<ProviderSymbol>> symbolsByPath,
            ProviderSymbol symbol,
            Map<String, IndexedRelation> relations) throws IOException {
        JsonNode prepared = session.request("textDocument/prepareTypeHierarchy", messages.positionParams(symbol));
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

    void collectCallHierarchy(
            Session session,
            Path projectRoot,
            Map<String, List<ProviderSymbol>> symbolsByPath,
            ProviderSymbol symbol,
            Map<String, IndexedRelation> relations) throws IOException {
        JsonNode prepared = session.request("textDocument/prepareCallHierarchy", messages.positionParams(symbol));
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

    void addRelation(
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

}
