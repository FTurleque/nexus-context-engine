package com.nexus.integrations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Génère des configurations clientes pour le serveur MCP NEXUS sans modifier
 * les fichiers ou préférences de l'utilisateur.
 */
public final class AssistantIntegrationGenerator {

    public static final String SERVER_NAME = "nexus";

    private final ObjectMapper objectMapper;

    public AssistantIntegrationGenerator() {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public String copilotCliCommand(Path runner) {
        return "copilot mcp add " + SERVER_NAME + " --tools \"*\" -- java -jar " + quote(normalize(runner));
    }

    public String copilotCliJson(Path runner) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("type", "stdio");
        server.put("command", "java");
        server.put("args", List.of("-jar", normalize(runner)));
        server.put("env", Map.of());
        server.put("tools", List.of("*"));
        return json(Map.of("mcpServers", Map.of(SERVER_NAME, server)));
    }

    public String copilotJetBrainsJson(Path runner) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("type", "stdio");
        server.put("command", "java");
        server.put("args", List.of("-jar", normalize(runner)));
        return json(Map.of("servers", Map.of(SERVER_NAME, server)));
    }

    public String claudeProjectCommand(Path runner) {
        return "claude mcp add " + SERVER_NAME + " --scope project -- java -jar " + quote(normalize(runner));
    }

    public String claudeUserCommand(Path runner) {
        return "claude mcp add " + SERVER_NAME + " --scope user -- java -jar " + quote(normalize(runner));
    }

    public String claudeProjectJson(Path runner) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("type", "stdio");
        server.put("command", "java");
        server.put("args", List.of("-jar", normalize(runner)));
        server.put("env", Map.of());
        return json(Map.of("mcpServers", Map.of(SERVER_NAME, server)));
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Impossible de sérialiser la configuration d'intégration", exception);
        }
    }

    private static String normalize(Path runner) {
        Objects.requireNonNull(runner, "runner");
        return runner.toAbsolutePath().normalize().toString();
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\\\"") + '"';
    }
}
