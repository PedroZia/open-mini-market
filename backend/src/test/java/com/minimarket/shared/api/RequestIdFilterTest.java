package com.minimarket.shared.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
class RequestIdFilterTest {

  @Test
  @DisplayName("gera e devolve um X-Request-Id quando o cliente não envia")
  void generatesRequestIdWhenAbsent() {
    given()
        .when()
        .get("/test/errors/not-found")
        .then()
        .statusCode(404)
        .header(RequestIdFilter.REQUEST_ID_HEADER, not(emptyOrNullString()));
  }

  @Test
  @DisplayName("ecoa o X-Request-Id do cliente e usa o mesmo valor no corpo do erro")
  void echoesClientRequestIdInErrorBody() {
    String requestId = UUID.randomUUID().toString();

    given()
        .header(RequestIdFilter.REQUEST_ID_HEADER, requestId)
        .when()
        .get("/test/errors/not-found")
        .then()
        .statusCode(404)
        .header(RequestIdFilter.REQUEST_ID_HEADER, equalTo(requestId))
        .body("traceId", equalTo(requestId));
  }

  @Test
  @DisplayName("rota inexistente também devolve o X-Request-Id")
  void unknownRouteAlsoEchoesRequestId() {
    String requestId = UUID.randomUUID().toString();

    given()
        .header(RequestIdFilter.REQUEST_ID_HEADER, requestId)
        .when()
        .get("/api/v1/nao-existe")
        .then()
        .statusCode(404)
        .header(RequestIdFilter.REQUEST_ID_HEADER, equalTo(requestId))
        .body("traceId", equalTo(requestId));
  }

  @Test
  @DisplayName("resposta de sucesso também devolve o X-Request-Id")
  void successResponseAlsoEchoesRequestId() {
    String requestId = UUID.randomUUID().toString();

    given()
        .header(RequestIdFilter.REQUEST_ID_HEADER, requestId)
        .contentType("application/json")
        .body("{\"name\": \"válido\"}")
        .when()
        .post("/test/errors/validation")
        .then()
        .statusCode(200)
        .header(RequestIdFilter.REQUEST_ID_HEADER, equalTo(requestId));
  }
}
