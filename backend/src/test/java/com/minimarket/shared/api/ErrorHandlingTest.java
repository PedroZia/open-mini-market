package com.minimarket.shared.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ErrorHandlingTest {

  @Test
  @DisplayName("404 de recurso inexistente vira problem+json com code e traceId")
  void notFoundBecomesProblemJson() {
    given()
        .when()
        .get("/test/errors/not-found")
        .then()
        .statusCode(404)
        .contentType(containsString("application/problem+json"))
        .body("type", equalTo("https://minimarket.local/problems/not-found"))
        .body("title", equalTo("Recurso não encontrado"))
        .body("status", equalTo(404))
        .body("detail", equalTo("o recurso 42 não existe"))
        .body("instance", equalTo("/test/errors/not-found"))
        .body("code", equalTo("NOT_FOUND"))
        .body("traceId", not(emptyOrNullString()));
  }

  @Test
  @DisplayName("rota inexistente também responde no formato do problema")
  void unknownRouteBecomesProblemJson() {
    given()
        .when()
        .get("/api/v1/nao-existe")
        .then()
        .statusCode(404)
        .contentType(containsString("application/problem+json"))
        .body("code", equalTo("NOT_FOUND"))
        .body("traceId", not(emptyOrNullString()));
  }

  @Test
  @DisplayName("400 de validação lista os campos em errors[]")
  void validationBecomesProblemJson() {
    given()
        .contentType("application/json")
        .body("{\"name\": \"\"}")
        .when()
        .post("/test/errors/validation")
        .then()
        .statusCode(400)
        .contentType(containsString("application/problem+json"))
        .body("code", equalTo("VALIDATION_ERROR"))
        .body("errors[0].field", equalTo("name"))
        .body("errors[0].message", equalTo("não pode ser vazio"))
        .body("traceId", not(emptyOrNullString()));
  }

  @Test
  @DisplayName("405, 409 e 422 usam os códigos estáveis")
  void otherErrorsUseStableCodes() {
    given()
        .when()
        .post("/test/errors/not-found")
        .then()
        .statusCode(405)
        .body("code", equalTo("METHOD_NOT_ALLOWED"));

    given()
        .when()
        .get("/test/errors/conflict")
        .then()
        .statusCode(409)
        .body("code", equalTo("CONFLICT"));

    given()
        .when()
        .get("/test/errors/business")
        .then()
        .statusCode(422)
        .body("code", equalTo("BUSINESS_ERROR"));
  }

  @Test
  @DisplayName("500 responde INTERNAL_ERROR sem vazar o detalhe interno")
  void unexpectedErrorDoesNotLeakDetail() {
    given()
        .when()
        .get("/test/errors/boom")
        .then()
        .statusCode(500)
        .contentType(containsString("application/problem+json"))
        .body("code", equalTo("INTERNAL_ERROR"))
        .body("detail", equalTo("Erro interno inesperado"))
        .body(not(containsString("detalhe interno que não pode vazar")))
        .body("traceId", not(emptyOrNullString()));
  }
}
