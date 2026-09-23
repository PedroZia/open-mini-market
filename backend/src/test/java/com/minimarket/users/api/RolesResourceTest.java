package com.minimarket.users.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * API de administração de papéis (passo 114) contra PostgreSQL real (Dev Services). Diferente dos
 * testes de repositório, o PUT daqui commita de verdade: cada teste que muta restaura o mapa de
 * OPERADOR semeado por {@code V3__rbac.sql} no fim, para os testes de RBAC/migração/usuários que
 * rodarem depois encontrarem o banco como estava.
 */
@QuarkusTest
class RolesResourceTest {

  /** Índice de OPERADOR no GET: o catálogo é ordenado por código (ADMIN, GERENTE, OPERADOR). */
  private static final int OPERADOR_INDEX = 2;

  /** As 10 permissões de OPERADOR no mapa de §4.5, o mesmo semeado por {@code V3__rbac.sql}. */
  private static final Set<String> OPERADOR_PERMISSIONS =
      Set.of(
          "product.read",
          "sale.create",
          "payment.add",
          "sale.complete",
          "cash.read",
          "cash.open",
          "cash.close",
          "customer.read",
          "customer.write",
          "stock.read");

  @Test
  @DisplayName("GET /api/v1/roles responde 200 com as 3 roles do seed e as permissões de cada uma")
  void listsRoles() {
    Response response = listRoles();

    assertThat(response.contentType()).contains("application/json");
    assertThat(response.jsonPath().getList("code", String.class))
        .containsExactly("ADMIN", "GERENTE", "OPERADOR");
    assertThat(response.jsonPath().getList("$")).hasSize(3);

    assertThat(response.jsonPath().getString("[0].name")).isEqualTo("Administrador");
    assertThat(response.jsonPath().getString("[0].description"))
        .isEqualTo("Acesso total ao sistema");
    assertThat(response.jsonPath().getBoolean("[0].system")).isTrue();
    assertThat(response.jsonPath().getList("[0].permissions", String.class))
        .hasSize(26)
        .contains("role.write", "user.session.revoke", "user.read")
        .isSorted();

    assertThat(response.jsonPath().getList("[1].permissions", String.class))
        .hasSize(22)
        .contains("sale.discount.apply", "sale.refund", "audit.read", "report.read")
        .isSorted();

    assertThat(
            response.jsonPath().getList("[%d].permissions".formatted(OPERADOR_INDEX), String.class))
        .containsExactlyInAnyOrderElementsOf(OPERADOR_PERMISSIONS)
        .isSorted();
  }

  @Test
  @DisplayName(
      "PUT /api/v1/roles/{code}/permissions responde 200 e o GET reflete o novo conjunto da role")
  void replacesPermissionsAndListReflects() {
    Response response =
        putPermissions(
            "OPERADOR",
            """
            {"permissions": [" stock.adjust ", "report.read", "stock.adjust"]}
            """,
            200);

    // role semeada (system = true) é editável: é o propósito da tabela role_permissions
    assertThat(response.jsonPath().getString("code")).isEqualTo("OPERADOR");
    assertThat(response.jsonPath().getString("name")).isEqualTo("Operador");
    assertThat(response.jsonPath().getBoolean("system")).isTrue();
    assertThat(response.jsonPath().getList("permissions", String.class))
        .containsExactly("report.read", "stock.adjust");

    assertThat(operadorPermissionsFromList()).containsExactly("report.read", "stock.adjust");
  }

  @Test
  @DisplayName("PUT com lista vazia remove todas as permissões da role e o GET reflete")
  void clearsPermissions() {
    Response response =
        putPermissions(
            "OPERADOR",
            """
            {"permissions": []}
            """,
            200);

    assertThat(response.jsonPath().getList("permissions")).isEmpty();
    assertThat(operadorPermissionsFromList()).isEmpty();
  }

  @Test
  @DisplayName("PUT com permissão inexistente responde 400 UNKNOWN_PERMISSION e não grava nada")
  void rejectsUnknownPermission() {
    Response response =
        putPermissions(
            "OPERADOR",
            """
            {"permissions": ["product.read", "nao.existe", "outro.fantasma"]}
            """,
            400);

    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("type"))
        .isEqualTo("https://minimarket.local/problems/unknown-permission");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Permissão desconhecida");
    assertThat(response.jsonPath().getInt("status")).isEqualTo(400);
    assertThat(response.jsonPath().getString("code")).isEqualTo("UNKNOWN_PERMISSION");
    assertThat(response.jsonPath().getString("detail"))
        .contains("nao.existe")
        .contains("outro.fantasma");

    assertThat(operadorPermissionsFromList())
        .containsExactlyInAnyOrderElementsOf(OPERADOR_PERMISSIONS);
  }

  @Test
  @DisplayName("PUT de role inexistente responde 404 ROLE_NOT_FOUND")
  void rejectsUnknownRole() {
    Response response =
        putPermissions(
            "FANTASMA",
            """
            {"permissions": ["product.read"]}
            """,
            404);

    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("type"))
        .isEqualTo("https://minimarket.local/problems/role-not-found");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Papel não encontrado");
    assertThat(response.jsonPath().getInt("status")).isEqualTo(404);
    assertThat(response.jsonPath().getString("code")).isEqualTo("ROLE_NOT_FOUND");
  }

  @Test
  @DisplayName("PUT sem o campo permissions responde 400 VALIDATION_ERROR sem gravar nada")
  void rejectsMissingPermissions() {
    Response response = putPermissions("OPERADOR", "{}", 400);

    assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    assertThat(response.jsonPath().getList("errors.field", String.class))
        .containsExactly("permissions");

    Response nullField =
        putPermissions(
            "OPERADOR",
            """
            {"permissions": null}
            """,
            400);
    assertThat(nullField.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");

    assertThat(operadorPermissionsFromList())
        .containsExactlyInAnyOrderElementsOf(OPERADOR_PERMISSIONS);
  }

  /**
   * Devolve o mapa de OPERADOR ao seed depois de cada teste: os testes de repositório usam
   * {@code @TestTransaction} e assumem o catálogo como a migration o deixou.
   */
  @AfterEach
  void restoresOperadorSeed() {
    String codes =
        OPERADOR_PERMISSIONS.stream()
            .sorted()
            .map(code -> "\"%s\"".formatted(code))
            .collect(Collectors.joining(", "));
    putPermissions("OPERADOR", "{\"permissions\": [%s]}".formatted(codes), 200);
  }

  private static Response listRoles() {
    return given().when().get("/api/v1/roles").then().statusCode(200).extract().response();
  }

  /** Permissões de OPERADOR segundo o GET, o que confirma que a troca persistiu e é visível. */
  private static List<String> operadorPermissionsFromList() {
    return listRoles()
        .jsonPath()
        .getList("[%d].permissions".formatted(OPERADOR_INDEX), String.class);
  }

  /** PUT com corpo bruto; espera o status informado e devolve a resposta. */
  private static Response putPermissions(String code, String body, int expectedStatus) {
    return given()
        .contentType("application/json")
        .body(body)
        .when()
        .put("/api/v1/roles/{code}/permissions", code)
        .then()
        .statusCode(expectedStatus)
        .extract()
        .response();
  }
}
