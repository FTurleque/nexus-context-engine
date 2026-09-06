package com.nexus.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class NexusProjectStateConflictIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void searchBeforeIndexingReturnsExplicitConflict() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("not-ready-project"));
        Files.writeString(projectRoot.resolve("README.md"), "# not ready yet\n");

        String projectId = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "rootPath", projectRoot.toString(),
                        "name", "not-ready-" + UUID.randomUUID()))
                .when()
                .post("/api/v1/projects")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("query", "README", "limit", 1))
                .when()
                .post("/api/v1/projects/{projectId}/search", projectId)
                .then()
                .statusCode(409)
                .body("error", equalTo("project_not_ready"))
                .body("message", equalTo("Le projet n'est pas prêt pour cette opération"));
    }
}
