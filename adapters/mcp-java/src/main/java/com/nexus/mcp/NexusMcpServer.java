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
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

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

    public static void main(String[] args) throws SQLException, IOException, InterruptedException {
        NexusApplication application = NexusApplication.createLongLived(NexusPaths.fromEnvironment());
        NexusMcpTools tools = new NexusMcpTools(application, new ObjectMapper());
        List<McpServerFeatures.SyncToolSpecification> specifications = tools.specifications();
        CountDownLatch lifecycle = new CountDownLatch(1);
        AtomicBoolean resourcesClosed = new AtomicBoolean(false);

        McpStdioInputStream lifecycleInput = new McpStdioInputStream(
                System.in,
                McpStdioInputStream.DEFAULT_MAX_FRAME_BYTES,
                lifecycle::countDown);
        StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(
                McpJsonDefaults.getMapper(),
                lifecycleInput,
                System.out);

        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo("nexus-context-engine", version())
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(false)
                        .build())
                .tools(specifications.toArray(McpServerFeatures.SyncToolSpecification[]::new))
                .build();

        Thread shutdownHook = new Thread(
                () -> closeResourcesOnce(server, application, resourcesClosed),
                "nexus-mcp-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            lifecycle.await();
        } finally {
            closeResourcesOnce(server, application, resourcesClosed);
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException shutdownInProgress) {
                // La JVM est déjà en cours d'arrêt : le hook exécute la même fermeture idempotente.
            }
        }
    }

    private static void closeResourcesOnce(
            McpSyncServer server,
            NexusApplication application,
            AtomicBoolean resourcesClosed) {
        if (resourcesClosed.compareAndSet(false, true)) {
            closeResources(server, application);
        }
    }

    private static void closeResources(McpSyncServer server, NexusApplication application) {
        try {
            server.closeGracefully();
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
        return implementationVersion == null || implementationVersion.isBlank()
                ? "0.0.0-dev"
                : implementationVersion;
    }
}
