package com.nexus.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Scénarios de {@code rest-transport-hardening} sur le listener loopback en mode jeton.
 *
 * <p>Le filtre d'authentification lit le jeton à chaque requête dans la propriété système
 * {@link NexusRestSecurity#TOKEN_PROPERTY} : chaque test le pose puis le retire, sur la même
 * application que les autres classes {@code @QuarkusTest}. Un profil Quarkus dédié imposerait un
 * redémarrage dans un nouveau chargeur de classes, où le pilote JDBC SQLite enregistré par le
 * premier n'est plus visible. Le contrôle de transport ne dépend pas du mode trust-local ; la
 * validation au démarrage du mode jeton loopback reste couverte par
 * {@code NexusRestExposureGuardTest}.</p>
 */
@QuarkusTest
class NexusRestLoopbackTransportTokenTest {

    static final String STRONG_TOKEN = "6df1462d571a6925e3bc3934ee10c6c55a965116fb47e2bc4db77ac7a5d69d34";
    private static final String BEARER = "Bearer " + STRONG_TOKEN;

    private String previousToken;

    @BeforeEach
    void configureLoopbackToken() {
        previousToken = System.getProperty(NexusRestSecurity.TOKEN_PROPERTY);
        System.setProperty(NexusRestSecurity.TOKEN_PROPERTY, STRONG_TOKEN);
    }

    @AfterEach
    void restoreToken() {
        if (previousToken == null) {
            System.clearProperty(NexusRestSecurity.TOKEN_PROPERTY);
        } else {
            System.setProperty(NexusRestSecurity.TOKEN_PROPERTY, previousToken);
        }
    }

    @Test
    void authenticatedLoopbackRequestIsServed() {
        given()
                .header("Authorization", BEARER)
                .header("Host", "127.0.0.1:8080")
                .when()
                .get("/api/v1/projects")
                .then()
                .statusCode(200);
    }

    @Test
    void authenticationStillAppliesOnLoopbackHost() {
        given()
                .when()
                .get("/api/v1/projects")
                .then()
                .statusCode(401);
    }

    @Test
    void foreignHostIsRefusedEvenWithAValidToken() {
        String body = given()
                .header("Authorization", BEARER)
                .header("Host", NexusRestLoopbackTransportTrustLocalTest.FOREIGN_HOST)
                .when()
                .get("/api/v1/projects")
                .then()
                .statusCode(403)
                .body("error", equalTo("FORBIDDEN"))
                .extract()
                .asString();
        NexusRestLoopbackTransportTrustLocalTest.assertNoTransportDetail(body);
    }

    @Test
    void foreignHostIsRefusedBeforeAuthentication() {
        given()
                .header("Host", NexusRestLoopbackTransportTrustLocalTest.FOREIGN_HOST)
                .when()
                .get("/api/v1/projects")
                .then()
                .statusCode(403);
    }

    @Test
    void foreignOriginIsRefusedEvenWithAValidToken() {
        given()
                .header("Authorization", BEARER)
                .header("Origin", "https://" + NexusRestLoopbackTransportTrustLocalTest.FOREIGN_HOST)
                .when()
                .get("/api/v1/projects")
                .then()
                .statusCode(403);
    }

    @Test
    void authenticatedRequestWithoutOriginIsServed() {
        given()
                .header("Authorization", BEARER)
                .when()
                .get("/api/v1/projects")
                .then()
                .statusCode(200);
    }
}
