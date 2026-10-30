package com.nexus.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.config.NexusPaths;
import com.nexus.exposure.PublicOutput;
import com.nexus.exposure.PublicOutputSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Parité de la CLI JSON avec le contrat de {@link PublicOutputSchema}. REST
 * ({@code NexusRestOutputParityTest}) et MCP ({@code NexusMcpOutputParityTest}) vérifient le même
 * contrat sur la même fixture : les trois canaux exposent donc les mêmes clés.
 */
class CliOutputParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String QUERY = "OrderService process order";
    private static final List<String> ENVELOPE = List.of(PublicOutputSchema.CLI_COMMAND_FIELD);

    @TempDir
    Path temporary;

    private String previousHome;

    @BeforeEach
    void indexFixture() throws Exception {
        previousHome = System.getProperty(NexusPaths.HOME_PROPERTY);
        System.setProperty(NexusPaths.HOME_PROPERTY, temporary.resolve("home").toString());
        for (String name : List.of("first", "second")) {
            Path root = Files.createDirectories(temporary.resolve(name).resolve("src/main/java/demo"));
            Files.writeString(root.resolve("OrderService.java"), """
                    package demo;

                    public class OrderService {
                        public String process(String order) {
                            return order;
                        }
                    }
                    """);
            assertEquals(NexusCli.EXIT_SUCCESS, cli("project", "add", temporary.resolve(name).toString(), name));
            assertEquals(NexusCli.EXIT_SUCCESS, cli("index", name));
        }
    }

    @AfterEach
    void restoreHome() {
        if (previousHome == null) {
            System.clearProperty(NexusPaths.HOME_PROPERTY);
        } else {
            System.setProperty(NexusPaths.HOME_PROPERTY, previousHome);
        }
    }

    @Test
    void searchMatchesThePublicContract() throws Exception {
        Map<?, ?> payload = json("search", "first", QUERY, "--explain");
        assertFalse(((List<?>) payload.get("results")).isEmpty());
        assertEquals(List.of(), PublicOutputSchema.divergences(PublicOutput.Search.class, payload, ENVELOPE));
    }

    @Test
    void federatedSearchMatchesThePublicContract() throws Exception {
        Map<?, ?> payload = json("search-federated", "first,second", QUERY, "--explain");
        assertFalse(((List<?>) payload.get("results")).isEmpty());
        assertEquals(List.of(), PublicOutputSchema.divergences(PublicOutput.FederatedSearch.class, payload, ENVELOPE));
    }

    @Test
    void contextMatchesThePublicContract() throws Exception {
        Map<?, ?> payload = json("context", "first", QUERY, "--explain");
        assertFalse(((List<?>) payload.get("items")).isEmpty());
        assertEquals(List.of(), PublicOutputSchema.divergences(PublicOutput.Context.class, payload, ENVELOPE));
    }

    @Test
    void federatedContextMatchesThePublicContract() throws Exception {
        Map<?, ?> payload = json("context-federated", "first,second", QUERY, "--explain");
        assertFalse(((List<?>) payload.get("items")).isEmpty());
        assertEquals(List.of(), PublicOutputSchema.divergences(PublicOutput.FederatedContext.class, payload, ENVELOPE));
    }

    @Test
    void indexMatchesThePublicContract() throws Exception {
        Map<?, ?> payload = json("index", "first");
        assertEquals(List.of(), PublicOutputSchema.divergences(PublicOutput.Index.class, payload, ENVELOPE));
    }

    private Map<?, ?> json(String... args) throws Exception {
        String[] withJson = java.util.Arrays.copyOf(args, args.length + 1);
        withJson[args.length] = "--json";
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) {
            assertEquals(NexusCli.EXIT_SUCCESS, NexusCli.execute(withJson, new ByteArrayInputStream(new byte[0]), out, err));
        }
        return JSON.readValue(stdout.toString(StandardCharsets.UTF_8), Map.class);
    }

    private static int cli(String... args) {
        try (PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) {
            return NexusCli.execute(args, new ByteArrayInputStream(new byte[0]), out, out);
        }
    }
}
