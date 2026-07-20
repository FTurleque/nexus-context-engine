package com.nexus.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;

@QuarkusTest
class NexusResourceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void exposesTheNexusFlowThroughRestWithoutLeakingCoreModels() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path source = projectRoot.resolve("src/main/java/demo/OrderService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package demo;
                public class OrderService {
                    public String processOrder(String id) {
                        return "processed-" + id;
                    }
                }
                """);

        String projectId = given()
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
                .extract()
                .path("id");

        given()
                .when()
                .post("/api/v1/projects/{projectId}/index", projectId)
                .then()
                .statusCode(200)
                .body("project.indexStatus", equalTo("READY"))
                .body("report.statistics.files", equalTo(1))
                .body("report.statistics.symbols", greaterThan(0));

        given()
                .when()
                .get("/api/v1/projects/{projectId}/index", projectId)
                .then()
                .statusCode(200)
                .body("files", equalTo(1))
                .body("symbols", greaterThan(0));

        given()
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
                .body("results[0].path", equalTo("src/main/java/demo/OrderService.java"))
                .body("results[0].reasons", hasSize(greaterThan(0)));

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("query", "OrderService"))
                .when()
                .post("/api/v1/projects/{projectId}/explain/search", projectId)
                .then()
                .statusCode(200)
                .body("explain", equalTo(true))
                .body("results", hasSize(greaterThan(0)));

        given()
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
                .body("items", hasSize(greaterThan(0)));

        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "query", "OrderService",
                        "tokenBudget", 200))
                .when()
                .post("/api/v1/projects/{projectId}/explain/context", projectId)
                .then()
                .statusCode(200)
                .body("explain", equalTo(true))
                .body("items", hasSize(greaterThan(0)));

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("query", " "))
                .when()
                .post("/api/v1/projects/{projectId}/search", projectId)
                .then()
                .statusCode(400)
                .body("error", equalTo("bad_request"));

        given()
                .when()
                .get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));

        given()
                .when()
                .get("/q/metrics")
                .then()
                .statusCode(200)
                .body(not(blankOrNullString()));
    }
}
