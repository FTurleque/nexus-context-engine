package com.nexus.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Point d'entrée du serveur MCP local NEXUS utilisant le transport STDIO officiel.
 *
 * <p>Aucun message applicatif n'est écrit sur stdout : ce flux est réservé au
 * framing JSON-RPC géré par le SDK MCP.</p>
 */
public final class NexusMcpServer {

    private static final System.Logger LOGGER = System.getLogger(NexusMcpServer.class.getName());

    private NexusMcpServer() {
    }

    public static void main(String[] args) throws Exception {
        NexusApplication application = NexusApplication.createLongLived(NexusPaths.fromEnvironment());
        NexusMcpTools tools = new NexusMcpTools(application, new ObjectMapper());
        List<McpServerFeatures.SyncToolSpecification> specifications = tools.specifications();

        StdioServerTransportProvider transportProvider =
                new StdioServerTransportProvider(McpJsonDefaults.getMapper());

        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo("nexus-context-engine", version())
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(false)
                        .build())
                .tools(specifications.toArray(McpServerFeatures.SyncToolSpecification[]::new))
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> closeResources(server, application),
                "nexus-mcp-shutdown"));
        new CountDownLatch(1).await();
    }

    private static void closeResources(McpSyncServer server, NexusApplication application) {
        try {
            server.close();
        } finally {
            try {
                application.close();
            } catch (IOException exception) {
                LOGGER.log(
                        System.Logger.Level.ERROR,
                        "Impossible de fermer les readers Lucene du serveur MCP",
                        exception);
            }
        }
    }

    private static String version() {
        String implementationVersion = NexusMcpServer.class.getPackage().getImplementationVersion();
        // Sans manifeste (exécution depuis les classes), on annonce explicitement un build de
        // développement plutôt qu'un numéro codé en dur qui dériverait à chaque montée de version.
        return implementationVersion == null || implementationVersion.isBlank()
                ? "0.0.0-dev"
                : implementationVersion;
    }
}
