package com.nexus.api;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class ManagementEndpointIsolationTest {

    // management=true already prefixes the configured management root path (/q).
    @TestHTTPResource(value = "/health/ready", management = true)
    URL readinessUrl;

    @TestHTTPResource(value = "/metrics", management = true)
    URL metricsUrl;

    @Test
    void exposesHealthAndMetricsOnlyOnTheDedicatedManagementListener() {
        given()
                .when()
                .get(readinessUrl.toString())
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));

        given()
                .when()
                .get(metricsUrl.toString())
                .then()
                .statusCode(200)
                .body(not(blankOrNullString()));
    }
}
