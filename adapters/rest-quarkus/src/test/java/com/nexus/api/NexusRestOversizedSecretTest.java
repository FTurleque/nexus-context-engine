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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

/** Une valeur sensible trop longue n'exclut que son fichier : l'indexation REST réussit sans 400. */
@QuarkusTest
class NexusRestOversizedSecretTest {

    @TempDir
    Path temporary;

    @Test
    void indexingSucceedsAndReportsTheExcludedFileWithoutItsValue() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("oversized"));
        Files.writeString(root.resolve("first.md"), "# firstMarker\n");
        Files.writeString(root.resolve("second.md"), "# secondMarker\n");
        Files.writeString(root.resolve("leak.md"), "# leakMarker\npassword = \"" + "w".repeat(5_000) + "\"\n");
        String id = given().contentType(ContentType.JSON)
                .body(Map.of("rootPath", root.toString(), "name", "oversized-" + UUID.randomUUID()))
                .when().post("/api/v1/projects")
                .then().statusCode(201).extract().path("id");

        given().when().post("/api/v1/projects/{projectId}/index", id)
                .then()
                .statusCode(200)
                .body("project.indexStatus", equalTo("READY"))
                .body("report.diagnostics", hasItem(
                        "INDEX_REDACTION_LIMIT: fichier exclu des index lexical et sémantique : leak.md"
                                + " (valeur sensible dépassant la limite de redaction)"))
                .body(not(containsString("wwwwwwww")));

        for (String marker : new String[]{"firstMarker", "secondMarker"}) {
            given().contentType(ContentType.JSON).body(Map.of("query", marker))
                    .when().post("/api/v1/projects/{projectId}/search", id)
                    .then().statusCode(200).body("results.path", hasItem(marker.replace("Marker", "") + ".md"));
        }
        given().contentType(ContentType.JSON).body(Map.of("query", "leakMarker"))
                .when().post("/api/v1/projects/{projectId}/search", id)
                .then().statusCode(200).body(not(containsString("wwwwwwww")));
    }
}
