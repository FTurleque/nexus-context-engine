package com.nexus.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.exposure.PublicOutput;
import com.nexus.exposure.PublicOutputSchema;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Parité des endpoints REST avec le contrat de {@link PublicOutputSchema}, sur la même fixture que
 * {@code CliOutputParityTest} et {@code NexusMcpOutputParityTest}. REST n'a aucune clé d'enveloppe.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NexusRestOutputParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String QUERY = "OrderService process order";

    @TempDir
    static Path classDirectory;

    private final List<String> projectIds = new ArrayList<>();

    /** Initialisation paresseuse : le port RestAssured n'est fixé qu'avant chaque test Quarkus. */
    @BeforeEach
    void indexFixtureOnce() throws Exception {
        if (!projectIds.isEmpty()) {
            return;
        }
        for (String name : List.of("first", "second")) {
            Path root = Files.createDirectories(classDirectory.resolve(name).resolve("src/main/java/demo"));
            Files.writeString(root.resolve("OrderService.java"), """
                    package demo;

                    public class OrderService {
                        public String process(String order) {
                            return order;
                        }
                    }
                    """);
            String id = given().contentType(ContentType.JSON)
                    .body(Map.of("rootPath", classDirectory.resolve(name).toString(),
                            "name", "parity-" + name + "-" + UUID.randomUUID()))
                    .when().post("/api/v1/projects")
                    .then().statusCode(201).extract().path("id");
            given().when().post("/api/v1/projects/{projectId}/index", id).then().statusCode(200);
            projectIds.add(id);
        }
    }

    @Test
    void searchMatchesThePublicContract() throws Exception {
        Map<?, ?> payload = post("/api/v1/projects/" + projectIds.getFirst() + "/explain/search", Map.of("query", QUERY));
        assertFalse(((List<?>) payload.get("results")).isEmpty());
        assertEquals(List.of(), PublicOutputSchema.divergences(PublicOutput.Search.class, payload, List.of()));
    }

    @Test
    void federatedSearchMatchesThePublicContract() throws Exception {
        Map<?, ?> payload = post("/api/v1/federated/search",
                Map.of("projectIds", projectIds, "query", QUERY, "explain", true));
        assertFalse(((List<?>) payload.get("results")).isEmpty());
        assertEquals(List.of(), PublicOutputSchema.divergences(PublicOutput.FederatedSearch.class, payload, List.of()));
    }

    @Test
    void contextMatchesThePublicContract() throws Exception {
        Map<?, ?> payload = post("/api/v1/projects/" + projectIds.getFirst() + "/explain/context", Map.of("query", QUERY));
        assertFalse(((List<?>) payload.get("items")).isEmpty());
        assertEquals(List.of(), PublicOutputSchema.divergences(PublicOutput.Context.class, payload, List.of()));
    }

    @Test
    void federatedContextMatchesThePublicContract() throws Exception {
        Map<?, ?> payload = post("/api/v1/federated/context",
                Map.of("projectIds", projectIds, "query", QUERY, "explain", true));
        assertFalse(((List<?>) payload.get("items")).isEmpty());
        assertEquals(List.of(), PublicOutputSchema.divergences(PublicOutput.FederatedContext.class, payload, List.of()));
    }

    @Test
    void indexAndProjectMatchThePublicContract() throws Exception {
        String index = given().when().post("/api/v1/projects/{projectId}/index", projectIds.getFirst())
                .then().statusCode(200).extract().asString();
        assertEquals(List.of(), PublicOutputSchema.divergences(PublicOutput.Index.class,
                JSON.readValue(index, Map.class), List.of()));
        String project = given().when().get("/api/v1/projects/{projectId}", projectIds.getFirst())
                .then().statusCode(200).extract().asString();
        assertEquals(List.of(), PublicOutputSchema.divergences(PublicOutput.Project.class,
                JSON.readValue(project, Map.class), List.of()));
    }

    private static Map<?, ?> post(String path, Map<String, Object> body) throws Exception {
        String json = given().contentType(ContentType.JSON).body(body)
                .when().post(path)
                .then().statusCode(200).extract().asString();
        return JSON.readValue(json, Map.class);
    }
}
