package com.nexus.index.jdt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;


/** Composant interne du provider JDT LS. */
final class JdtWorkspaceMessages {
    static ObjectNode initialize(ObjectMapper mapper, Path projectRoot) {
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

        return params;
    }
}
