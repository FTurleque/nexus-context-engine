package com.nexus.index.jdt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.nexus.index.jdt.JdtSymbolMapper.ProviderSymbol;

/** Composant interne du provider JDT LS. */
final class JdtDocumentMessages {
    private final ObjectMapper objectMapper;
    JdtDocumentMessages(ObjectMapper mapper) { this.objectMapper = mapper; }
    ObjectNode positionParams(ProviderSymbol symbol) {
        ObjectNode params = textDocumentParams(symbol.uri());
        ObjectNode position = objectMapper.createObjectNode();
        position.put("line", symbol.selectionLineZeroBased());
        position.put("character", symbol.selectionCharacter());
        params.set("position", position);
        return params;
    }

    ObjectNode textDocumentParams(String uri) {
        ObjectNode params = objectMapper.createObjectNode();
        ObjectNode textDocument = objectMapper.createObjectNode();
        textDocument.put("uri", uri);
        params.set("textDocument", textDocument);
        return params;
    }

    ObjectNode didOpenParams(String uri, String content) {
        ObjectNode params = objectMapper.createObjectNode();
        ObjectNode textDocument = objectMapper.createObjectNode();
        textDocument.put("uri", uri);
        textDocument.put("languageId", "java");
        textDocument.put("version", 1);
        textDocument.put("text", content);
        params.set("textDocument", textDocument);
        return params;
    }

    ObjectNode didCloseParams(String uri) {
        return textDocumentParams(uri);
    }

}
