package com.nexus.integrations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.Path;
import java.util.ArrayList;
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
    public static final String NATIVE_ACCESS_ARGUMENT = "--enable-native-access=ALL-UNNAMED";

    private final ObjectMapper objectMapper;

    public AssistantIntegrationGenerator() {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println(usage());
            return;
        }

        AssistantIntegrationGenerator generator = new AssistantIntegrationGenerator();
        String profile = args[0];

        // Backward-compatible Phase 6 syntax:
        // <profile> <runner-mcp> [command|json|toml]
        if (!"native".equals(args[1]) && !"docker".equals(args[1])) {
            Path runner = Path.of(args[1]);
            String format = args.length >= 3 ? args[2] : "command";
            System.out.println(generator.render(profile, generator.legacyJava(runner), format));
            return;
        }

        CommandSpec commandSpec;
        String format;
        if ("native".equals(args[1])) {
            if (args.length < 4) {
                throw new IllegalArgumentException("Le mode native exige <java-exe> <runner-mcp>.");
            }
            commandSpec = generator.nativeMcp(Path.of(args[2]), Path.of(args[3]));
            format = args.length >= 5 ? args[4] : "command";
        } else {
            if (args.length < 3) {
                throw new IllegalArgumentException("Le mode docker exige <container-name>.");
            }
            commandSpec = generator.dockerMcp(args[2]);
            format = args.length >= 4 ? args[3] : "command";
        }

        System.out.println(generator.render(profile, commandSpec, format));
    }

    private String render(String profile, CommandSpec commandSpec, String format) {
        return switch (profile) {
            case "copilot-cli" -> "json".equals(format)
                    ? copilotCliJson(commandSpec)
                    : copilotCliCommand(commandSpec);
            case "copilot-jetbrains" -> copilotJetBrainsJson(commandSpec);
            case "claude-project" -> "json".equals(format)
                    ? claudeProjectJson(commandSpec)
                    : claudeProjectCommand(commandSpec);
            case "claude-cli", "claude-user" -> claudeUserCommand(commandSpec);
            case "codex-desktop" -> "command".equals(format)
                    ? codexCommand(commandSpec)
                    : codexDesktopToml(commandSpec);
            case "generic" -> genericMcpJson(commandSpec);
            default -> throw new IllegalArgumentException("Profil inconnu : " + profile);
        };
    }

    public CommandSpec nativeMcp(Path javaExecutable, Path runner) {
        return new CommandSpec(
                normalize(javaExecutable),
                List.of(NATIVE_ACCESS_ARGUMENT, "-jar", normalize(runner)));
    }

    public CommandSpec dockerMcp(String containerName) {
        String container = Objects.requireNonNull(containerName, "containerName").trim();
        if (container.isEmpty()) {
            throw new IllegalArgumentException("containerName ne peut pas être vide");
        }
        return new CommandSpec("docker", List.of(
                "exec", "-i", container,
                "java", NATIVE_ACCESS_ARGUMENT, "-jar", "/opt/nexus/lib/nexus-mcp.jar"));
    }

    public String copilotCliCommand(Path runner) {
        return copilotCliCommand(legacyJava(runner));
    }

    public String copilotCliCommand(CommandSpec commandSpec) {
        return "copilot mcp add " + SERVER_NAME + " --tools \"*\" -- " + renderCommand(commandSpec);
    }

    public String copilotCliJson(Path runner) {
        return copilotCliJson(legacyJava(runner));
    }

    public String copilotCliJson(CommandSpec commandSpec) {
        Map<String, Object> server = stdioServer(commandSpec);
        server.put("env", Map.of());
        server.put("tools", List.of("*"));
        return json(Map.of("mcpServers", Map.of(SERVER_NAME, server)));
    }

    public String copilotJetBrainsJson(Path runner) {
        return copilotJetBrainsJson(legacyJava(runner));
    }

    public String copilotJetBrainsJson(CommandSpec commandSpec) {
        return json(Map.of("servers", Map.of(SERVER_NAME, stdioServer(commandSpec))));
    }

    public String claudeProjectCommand(Path runner) {
        return claudeProjectCommand(legacyJava(runner));
    }

    public String claudeProjectCommand(CommandSpec commandSpec) {
        return "claude mcp add --scope project " + SERVER_NAME + " -- " + renderCommand(commandSpec);
    }

    public String claudeUserCommand(Path runner) {
        return claudeUserCommand(legacyJava(runner));
    }

    public String claudeUserCommand(CommandSpec commandSpec) {
        return "claude mcp add --scope user " + SERVER_NAME + " -- " + renderCommand(commandSpec);
    }

    public String claudeProjectJson(Path runner) {
        return claudeProjectJson(legacyJava(runner));
    }

    public String claudeProjectJson(CommandSpec commandSpec) {
        Map<String, Object> server = stdioServer(commandSpec);
        server.put("env", Map.of());
        return json(Map.of("mcpServers", Map.of(SERVER_NAME, server)));
    }

    public String codexCommand(CommandSpec commandSpec) {
        return "codex mcp add " + SERVER_NAME + " -- " + renderCommand(commandSpec);
    }

    public String codexDesktopToml(CommandSpec commandSpec) {
        StringBuilder result = new StringBuilder();
        result.append("[mcp_servers.").append(SERVER_NAME).append("]\n");
        result.append("command = \"").append(tomlEscape(commandSpec.command())).append("\"\n");
        result.append("args = [");
        for (int i = 0; i < commandSpec.args().size(); i++) {
            if (i > 0) {
                result.append(", ");
            }
            result.append('"').append(tomlEscape(commandSpec.args().get(i))).append('"');
        }
        result.append("]\n");
        return result.toString();
    }

    public String genericMcpJson(CommandSpec commandSpec) {
        return json(Map.of("mcpServers", Map.of(SERVER_NAME, stdioServer(commandSpec))));
    }

    private CommandSpec legacyJava(Path runner) {
        return new CommandSpec("java", List.of(NATIVE_ACCESS_ARGUMENT, "-jar", normalize(runner)));
    }

    private static Map<String, Object> stdioServer(CommandSpec commandSpec) {
        Objects.requireNonNull(commandSpec, "commandSpec");
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("type", "stdio");
        server.put("command", commandSpec.command());
        server.put("args", commandSpec.args());
        return server;
    }

    /**
     * Rendu volontairement limité au sous-ensemble commun à cmd.exe et PowerShell.
     * Les caractères dont l'expansion diffère entre les deux shells sont refusés :
     * pour eux, JSON/TOML (ou les scripts PowerShell du wizard, qui passent un tableau
     * argv) sont la représentation sûre.
     */
    static String renderCommand(CommandSpec commandSpec) {
        List<String> parts = new ArrayList<>();
        parts.add(quotePortable(commandSpec.command()));
        for (String arg : commandSpec.args()) {
            parts.add(quotePortable(arg));
        }
        return String.join(" ", parts);
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Impossible de sérialiser la configuration d'intégration", exception);
        }
    }

    private static String normalize(Path path) {
        Objects.requireNonNull(path, "path");
        return path.toAbsolutePath().normalize().toString();
    }

    private static String quotePortable(String value) {
        String normalized = Objects.requireNonNull(value, "value");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "La forme commande n'est pas portable entre cmd.exe et PowerShell pour un argument vide. "
                            + "Utilisez JSON/TOML ou les scripts de connexion générés.");
        }
        rejectShellAmbiguous(normalized);
        if (!containsShellSignificant(normalized)) {
            return normalized;
        }
        return '"' + normalized + '"';
    }

    private static void rejectShellAmbiguous(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '%' || character == '!' || character == '$'
                    || character == '`' || character == '"' || character == '\'') {
                throw new IllegalArgumentException(
                        "La forme commande n'est pas portable entre cmd.exe et PowerShell pour le caractère '"
                                + character + "'. Utilisez JSON/TOML ou les scripts de connexion générés.");
            }
            if (character == '\r' || character == '\n' || character == '\0') {
                throw new IllegalArgumentException("La forme commande refuse les caractères de contrôle.");
            }
        }
    }

    private static boolean containsShellSignificant(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == ' ' || character == '\t'
                    || character == '&' || character == '|' || character == '<' || character == '>'
                    || character == '^' || character == '(' || character == ')' || character == ';'
                    || character == ',') {
                return true;
            }
        }
        return false;
    }

    private static String tomlEscape(String value) {
        return Objects.requireNonNull(value, "value")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String usage() {
        return """
                Usage:
                  java -jar nexus-assistant-clients-...-runner.jar <profil> <runner-mcp> [command|json|toml]
                  java -jar nexus-assistant-clients-...-runner.jar <profil> native <java-exe> <runner-mcp> [command|json|toml]
                  java -jar nexus-assistant-clients-...-runner.jar <profil> docker <container-name> [command|json|toml]

                Profils:
                  copilot-cli       command ou json
                  copilot-jetbrains json
                  claude-project    command ou json
                  claude-cli        command (scope user)
                  claude-user       alias rétrocompatible de claude-cli
                  codex-desktop     command ou toml (~/.codex/config.toml)
                  generic           json mcpServers

                Le mode native permet de viser explicitement le Java embarqué NEXUS.
                Le mode docker utilise docker exec -i et conserve MCP en STDIO.
                Les commandes MCP Java générées appliquent automatiquement le contrat native-access qualifié.
                La forme command vise uniquement le sous-ensemble d'arguments portable cmd.exe/PowerShell ;
                utilisez JSON/TOML pour les chemins contenant %, !, $, backtick ou guillemets.
                Le générateur n'écrit aucun fichier et ne modifie aucune configuration utilisateur.
                """;
    }

    public record CommandSpec(String command, List<String> args) {
        public CommandSpec {
            command = Objects.requireNonNull(command, "command").trim();
            if (command.isEmpty()) {
                throw new IllegalArgumentException("command ne peut pas être vide");
            }
            args = List.copyOf(Objects.requireNonNull(args, "args"));
        }
    }
}
