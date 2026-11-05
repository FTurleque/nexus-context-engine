package com.nexus.api;

import com.nexus.context.ContextBudgetPolicy;
import com.nexus.search.QueryPolicy;
import com.nexus.search.ResultLimitPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Contrat REST de bout en bout : un test par endpoint public et par endpoint Quarkus attendu.
 *
 * <p>Le projet de test est enregistré et indexé une fois par classe ; la home NEXUS reste celle
 * isolée par Surefire ({@code nexus.home} sous {@code target/}). Aucun test ne suppose l'ordre
 * d'exécution : les tests qui créent ou réindexent un projet utilisent leur propre projet.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NexusResourceTest {

    private static final String SOURCE_PATH = "src/main/java/demo/OrderService.java";
    private static final String FIXTURE_SECRET = "synthetic-unquoted-secret-123456";
    private static final List<String> INTERNAL_MODEL_MARKERS = List.of(
            "com.nexus.",
            "ProjectDescriptor",
            "RankedCandidate",
            "IndexingReport",
            "FederatedSearchHit",
            "CodeSymbol");

    @TempDir
    static Path classDirectory;

    private String projectId;

    /**
     * Prépare le projet de classe au premier test. Un {@code @BeforeAll} s'exécute avant que
     * Quarkus ne fixe le port de RestAssured : la fixture est donc créée paresseusement.
     */
    @BeforeEach
    void registerAndIndexTheFixtureProjectOnce() throws Exception {
        if (projectId != null) {
            return;
        }
        Path projectRoot = writeFixtureProject("fixture-project");
        String registered = register(projectRoot, "api-validation-fixture");
        given()
                .when()
                .post("/api/v1/projects/{projectId}/index", registered)
                .then()
                .statusCode(200)
                .body("project.indexStatus", equalTo("READY"));
        projectId = registered;
    }

    @Test
    void createProjectReturnsANotIndexedProjectWithoutRootPath() throws Exception {
        Path projectRoot = writeFixtureProject("create-" + UUID.randomUUID());

        String body = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "rootPath", projectRoot.toString(),
                        "name", "api-validation"))
                .when()
                .post("/api/v1/projects")
                .then()
                .statusCode(201)
                .body("name", equalTo("api-validation"))
                .body("indexStatus", equalTo("NOT_INDEXED"))
                .body("rootPath", nullValue())
                .extract()
                .asString();
        assertNoLeak(body);
    }

    @Test
    void listProjectsIncludesTheFixtureWithoutRootPaths() {
        String body = given()
                .when()
                .get("/api/v1/projects")
                .then()
                .statusCode(200)
                .body("id", hasItem(projectId))
                .body("rootPath", everyItem(nullValue()))
                .extract()
                .asString();
        assertNoLeak(body);
    }

    @Test
    void indexMarksAFreshProjectReadyAndReportsStatistics() throws Exception {
        // Réindexer mute l'état : ce test indexe son propre projet, pas la fixture de classe.
        String ownProjectId = register(writeFixtureProject("index-" + UUID.randomUUID()), "api-validation-index");

        String body = given()
                .when()
                .post("/api/v1/projects/{projectId}/index", ownProjectId)
                .then()
                .statusCode(200)
                .body("project.indexStatus", equalTo("READY"))
                .body("report.statistics.files", equalTo(1))
                .body("report.statistics.symbols", greaterThan(0))
                .extract()
                .asString();
        assertNoLeak(body);
    }

    @Test
    void inspectIndexReturnsTheFixtureStatistics() {
        String body = given()
                .when()
                .get("/api/v1/projects/{projectId}/index", projectId)
                .then()
                .statusCode(200)
                .body("files", equalTo(1))
                .body("symbols", greaterThan(0))
                .extract()
                .asString();
        assertNoLeak(body);
    }

    @Test
    void searchReturnsExplainedResultsWithPublicPaths() {
        String body = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "query", "OrderService process order",
                        "limit", 5,
                        "explain", true))
                .when()
                .post("/api/v1/projects/{projectId}/search", projectId)
                .then()
                .statusCode(200)
                .body("explain", equalTo(true))
                .body("results", hasSize(greaterThan(0)))
                .body("results[0].path", equalTo(SOURCE_PATH))
                .body("results[0].reasons", hasSize(greaterThan(0)))
                .extract()
                .asString();
        assertNoLeak(body);
    }

    @Test
    void searchRejectsBlankAndOversizedQueries() {
        String blank = given()
                .contentType(ContentType.JSON)
                .body(Map.of("query", " "))
                .when()
                .post("/api/v1/projects/{projectId}/search", projectId)
                .then()
                .statusCode(400)
                .body("error", equalTo("bad_request"))
                .extract()
                .asString();
        assertNoLeak(blank);

        String oversized = given()
                .contentType(ContentType.JSON)
                .body(Map.of("query", oversizedUtf8Query(), "limit", 1))
                .when()
                .post("/api/v1/projects/{projectId}/search", projectId)
                .then()
                .statusCode(400)
                .body("error", equalTo("bad_request"))
                .body("message", containsString("octets UTF-8"))
                .extract()
                .asString();
        assertNoLeak(oversized);
    }

    @Test
    void explainSearchForcesExplanation() {
        String body = given()
                .contentType(ContentType.JSON)
                .body(Map.of("query", "OrderService"))
                .when()
                .post("/api/v1/projects/{projectId}/explain/search", projectId)
                .then()
                .statusCode(200)
                .body("explain", equalTo(true))
                .body("results", hasSize(greaterThan(0)))
                .extract()
                .asString();
        assertNoLeak(body);
    }

    @Test
    void contextRespectsTheBudgetAndRedactsSecrets() {
        String body = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "query", "OrderService process order",
                        "tokenBudget", 200))
                .when()
                .post("/api/v1/projects/{projectId}/context", projectId)
                .then()
                .statusCode(200)
                .body("tokenBudget", equalTo(200))
                .body("estimatedTokens", lessThanOrEqualTo(200))
                .body(not(containsString("123456789")))
                .body(not(containsString(FIXTURE_SECRET)))
                .body(containsString("[REDACTED]"))
                .body("items", hasSize(greaterThan(0)))
                .extract()
                .asString();
        assertNoLeak(body);
    }

    @Test
    void contextRejectsConstraintsAndOversizedQueries() {
        String constrained = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "query", "OrderService",
                        "tokenBudget", 200,
                        "constraints", Map.of("language", "java")))
                .when()
                .post("/api/v1/projects/{projectId}/context", projectId)
                .then()
                .statusCode(400)
                .body("error", equalTo("bad_request"))
                .body("message", containsString("constraints are not supported"))
                .extract()
                .asString();
        assertNoLeak(constrained);

        String oversized = given()
                .contentType(ContentType.JSON)
                .body(Map.of("query", oversizedUtf8Query(), "tokenBudget", 100))
                .when()
                .post("/api/v1/projects/{projectId}/context", projectId)
                .then()
                .statusCode(400)
                .body("error", equalTo("bad_request"))
                .body("message", containsString("octets UTF-8"))
                .extract()
                .asString();
        assertNoLeak(oversized);
    }

    @Test
    void explainContextForcesExplanation() {
        String body = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "query", "OrderService",
                        "tokenBudget", 200))
                .when()
                .post("/api/v1/projects/{projectId}/explain/context", projectId)
                .then()
                .statusCode(200)
                .body("explain", equalTo(true))
                .body("items", hasSize(greaterThan(0)))
                .extract()
                .asString();
        assertNoLeak(body);
    }

    @Test
    void federatedSearchAcceptsTheMaximumLimitAndRejectsMaximumPlusOne() {
        String accepted = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "projectIds", List.of(projectId),
                        "query", "OrderService",
                        "limit", ResultLimitPolicy.MAX_RESULT_LIMIT))
                .when()
                .post("/api/v1/federated/search")
                .then()
                .statusCode(200)
                .body("projects", hasSize(1))
                .body("results", hasSize(greaterThan(0)))
                .body("results[0].result.path", equalTo(SOURCE_PATH))
                .extract()
                .asString();
        assertNoLeak(accepted);

        String rejected = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "projectIds", List.of(projectId),
                        "query", "OrderService",
                        "limit", ResultLimitPolicy.MAX_RESULT_LIMIT + 1))
                .when()
                .post("/api/v1/federated/search")
                .then()
                .statusCode(400)
                .body("error", equalTo("bad_request"))
                .extract()
                .asString();
        assertNoLeak(rejected);
    }

    @Test
    void federatedContextRespectsTheBudgetAndRejectsMaximumPlusOne() {
        String accepted = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "projectIds", List.of(projectId),
                        "query", "OrderService process order",
                        "tokenBudget", 200))
                .when()
                .post("/api/v1/federated/context")
                .then()
                .statusCode(200)
                .body("projects", hasSize(1))
                .body("tokenBudget", equalTo(200))
                .body("estimatedTokens", lessThanOrEqualTo(200))
                .body("items", hasSize(greaterThan(0)))
                .extract()
                .asString();
        assertNoLeak(accepted);

        String rejected = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "projectIds", List.of(projectId),
                        "query", "OrderService",
                        "tokenBudget", ContextBudgetPolicy.MAX_CONTEXT_TOKEN_BUDGET + 1))
                .when()
                .post("/api/v1/federated/context")
                .then()
                .statusCode(400)
                .body("error", equalTo("bad_request"))
                .extract()
                .asString();
        assertNoLeak(rejected);
    }

    // Health et métriques vivent désormais uniquement sur l'interface de management
    // loopback dédiée ; ils ne doivent plus être publiés par le listener applicatif.
    @Test
    void readinessIsNotPublishedOnTheApplicationListener() {
        String body = given().when().get("/q/health/ready").then().statusCode(404).extract().asString();
        assertNoLeak(body);
    }

    @Test
    void metricsAreNotPublishedOnTheApplicationListener() {
        String body = given().when().get("/q/metrics").then().statusCode(404).extract().asString();
        assertNoLeak(body);
    }

    private static String register(Path projectRoot, String name) {
        return given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "rootPath", projectRoot.toString(),
                        "name", name))
                .when()
                .post("/api/v1/projects")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private static Path writeFixtureProject(String directoryName) throws Exception {
        Path projectRoot = Files.createDirectories(classDirectory.resolve(directoryName));
        Path source = projectRoot.resolve(SOURCE_PATH);
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package demo;
                public class OrderService {
                    // {"password": 123456789, "api_key": synthetic-unquoted-secret-123456}
                    public String processOrder(String id) {
                        return "processed-" + id;
                    }
                }
                """);
        return projectRoot;
    }

    private static String oversizedUtf8Query() {
        return "é".repeat(QueryPolicy.MAX_QUERY_UTF8_BYTES / 2 + 1);
    }

    /**
     * Non-fuite commune : ni chemin absolu de la fixture (quelle que soit la forme des
     * séparateurs), ni secret de la fixture, ni type du modèle interne du cœur.
     */
    private static void assertNoLeak(String body) {
        String root = classDirectory.toAbsolutePath().toString();
        assertFalse(body.contains(root), body);
        assertFalse(body.contains(root.replace("\\", "\\\\")), body);
        assertFalse(body.contains(root.replace('\\', '/')), body);
        assertFalse(body.contains(FIXTURE_SECRET), body);
        for (String marker : INTERNAL_MODEL_MARKERS) {
            assertFalse(body.contains(marker), body);
        }
    }
}
