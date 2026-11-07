package com.nexus.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Scénarios de {@code rest-transport-hardening} sur le listener loopback en mode trust-local
 * (profil de test par défaut : {@code nexus.rest.trust-local=true}, aucun jeton).
 */
@QuarkusTest
class NexusRestLoopbackTransportTrustLocalTest {

    static final String FOREIGN_HOST = "attacker.example";

    @TempDir
    Path projectRoot;

    @Test
    void loopbackHostIsServedNormally() {
        for (String host : new String[]{"127.0.0.1:8080", "localhost", "[::1]:8080"}) {
            given()
                    .header("Host", host)
                    .when()
                    .get("/api/v1/projects")
                    .then()
                    .statusCode(200);
        }
    }

    @Test
    void foreignHostIsRefusedWithoutDetailAndRegistersNothing() throws Exception {
        Files.writeString(projectRoot.resolve("Marker.java"), "class Marker {}\n");
        String projectName = "transport-refused-" + projectRoot.getFileName();

        String body = given()
                .header("Host", FOREIGN_HOST + ":8080")
                .contentType(ContentType.JSON)
                .body(Map.of("rootPath", projectRoot.toString(), "name", projectName))
                .when()
                .post("/api/v1/projects")
                .then()
                .statusCode(403)
                .body("error", equalTo("FORBIDDEN"))
                .extract()
                .asString();
        assertNoTransportDetail(body);

        given()
                .header("Host", FOREIGN_HOST)
                .when()
                .get("/api/v1/projects")
                .then()
                .statusCode(403);

        given()
                .when()
                .get("/api/v1/projects")
                .then()
                .statusCode(200)
                .body(not(containsString(projectName)));
    }

    @Test
    void requestWithoutOriginIsServed() {
        given()
                .when()
                .get("/api/v1/projects")
                .then()
                .statusCode(200);
    }

    @Test
    void loopbackOriginIsServed() {
        given()
                .header("Origin", "http://localhost:3000")
                .when()
                .get("/api/v1/projects")
                .then()
                .statusCode(200);
    }

    @Test
    void foreignOriginIsRefusedWithoutDetail() {
        String body = given()
                .header("Origin", "https://" + FOREIGN_HOST)
                .when()
                .get("/api/v1/projects")
                .then()
                .statusCode(403)
                .body("error", equalTo("FORBIDDEN"))
                .extract()
                .asString();
        assertNoTransportDetail(body);
    }

    @Test
    void unknownRoutesAreRefusedBeforeMatching() {
        given()
                .header("Host", FOREIGN_HOST)
                .when()
                .get("/api/v1/does-not-exist")
                .then()
                .statusCode(403);
    }

    static void assertNoTransportDetail(String body) {
        for (String forbidden : new String[]{FOREIGN_HOST, "127.0.0.1", "localhost", "::1", "quarkus.http.host"}) {
            assertFalse(body.contains(forbidden),
                    "Le refus de transport ne doit pas divulguer " + forbidden + " : " + body);
        }
    }
}
