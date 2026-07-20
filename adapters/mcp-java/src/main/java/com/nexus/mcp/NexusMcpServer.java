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

import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Point d'entrée du serveur MCP local NEXUS utilisant le transport STDIO officiel.
 *
 * <p>Aucun message applicatif n'est écrit sur stdout : ce flux est réservé au
 * framing JSON-RPC géré par le SDK MCP.</p>
 */
public final class NexusMcpServer {

    private NexusMcpServer() {
    }

    public static void main(String[] args) throws Exception {
        NexusApplication application = NexusApplication.create(NexusPaths.fromEnvironment());
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

        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "nexus-mcp-shutdown"));
        new CountDownLatch(1).await();
    }

    private static String version() {
        String implementationVersion = NexusMcpServer.class.getPackage().getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank()
                ? "0.1.0-SNAPSHOT"
                : implementationVersion;
    }
}
