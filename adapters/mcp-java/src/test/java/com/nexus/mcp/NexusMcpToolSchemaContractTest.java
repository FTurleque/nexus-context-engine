package com.nexus.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NexusMcpToolSchemaContractTest {
    @TempDir Path temporary;

    @Test
    void preservesEveryToolNameParameterAndRequiredField() throws Exception {
        Map<String, Set<String>> expected = Map.of(
                "list_projects", Set.of(),
                "search_code", Set.of("project", "query", "limit", "explain"),
                "search_across_projects", Set.of("projects", "query", "limit", "explain"),
                "find_symbol", Set.of("project", "query", "limit"),
                "find_usages", Set.of("project", "symbol", "limit"),
                "build_context", Set.of("project", "query", "tokenBudget", "requestedSources", "constraints"),
                "explain_context", Set.of("project", "query", "tokenBudget", "requestedSources", "constraints"),
                "build_context_across_projects", Set.of("projects", "query", "tokenBudget", "requestedSources", "constraints"),
                "explain_context_across_projects", Set.of("projects", "query", "tokenBudget", "requestedSources", "constraints"));
        var mapper = new ObjectMapper();
        try (var application = NexusApplication.create(new NexusPaths(temporary))) {
            var tools = new NexusMcpTools(application, mapper).specifications();
            assertEquals(expected.size(), tools.size());
            for (var specification : tools) {
                var tool = mapper.valueToTree(specification.tool());
                String name = tool.path("name").asText();
                assertTrue(expected.containsKey(name));
                var schema = tool.path("inputSchema");
                assertEquals("object", schema.path("type").asText());
                assertFalse(schema.path("additionalProperties").asBoolean(true));
                Set<String> parameters = new HashSet<>();
                schema.path("properties").fieldNames().forEachRemaining(parameters::add);
                assertEquals(expected.get(name), parameters, name);
                Set<String> required = new HashSet<>();
                schema.path("required").forEach(value -> required.add(value.asText()));
                assertEquals(name.equals("list_projects") ? Set.of()
                        : Set.of(name.contains("across_projects") ? "projects" : "project",
                                name.equals("find_usages") ? "symbol" : "query"), required, name);
            }
        }
    }
}
