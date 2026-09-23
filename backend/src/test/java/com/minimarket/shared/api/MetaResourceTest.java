package com.minimarket.shared.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
class MetaResourceTest {

  @Test
  @DisplayName("GET /api/v1/meta responde 200 com os parâmetros da loja MATRIZ")
  void returnsMatrizMetadata() {
    Instant before = Instant.now();

    Response response =
        given().when().get("/api/v1/meta").then().statusCode(200).extract().response();

    Instant after = Instant.now();

    assertThat(response.contentType()).contains("application/json");
    assertThat(response.jsonPath().getString("apiVersion")).isEqualTo("v1");
    assertThat(response.jsonPath().getString("storeCode")).isEqualTo("MATRIZ");
    assertThat(response.jsonPath().getBoolean("allowNegativeStock")).isTrue();
    assertThat(new BigDecimal(response.jsonPath().get("maxDiscountPercent").toString()))
        .isEqualByComparingTo("100.00");

    String serverTime = response.jsonPath().getString("serverTime");
    assertThat(serverTime).endsWith("Z");
    assertThat(OffsetDateTime.parse(serverTime).toInstant()).isBetween(before, after);
  }
}
