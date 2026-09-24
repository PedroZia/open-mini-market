package com.minimarket.users.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minimarket.IntegrationTestBase;
import com.minimarket.support.TestAdmin;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Eventos de auditoria da troca do mapa de permissões de uma role (passo 310b) contra PostgreSQL
 * real (Dev Services): o PUT grava {@code ROLE_PERMISSIONS_CHANGED} na mesma transação do caso de
 * uso (§7.1/§7.2), com o código da role em {@code details} — role não tem UUID, então {@code
 * entityId} é nulo — e o antes/depois das permissões. Troca que repete o conjunto atual é no-op e
 * não inventa evento.
 *
 * <p>A conferência é por SQL, sempre filtrando por {@code action}: como {@code entity_id} é nulo
 * por definição, aqui não dá para isolar o alvo como no {@link UsersAuditEventsTest} — o teste olha
 * a diferença da contagem antes/depois e o último evento do log, deixando de fora os eventos da
 * fixture do ADMIN ({@code USER_CREATED}, {@code LOGIN_SUCCESS} e {@code SESSION_REVOKED}).
 *
 * <p>O PUT commita de verdade: cada teste restaura o mapa de OPERADOR semeado por {@code
 * V3__rbac.sql} no fim (mesmo padrão do {@link RolesResourceTest}), para os testes de
 * repositório/RBAC que rodarem depois encontrarem o catálogo como a migration o deixou. Os eventos
 * que ele cria saem junto com o ADMIN da fixture, que é o ator.
 */
@QuarkusTest
class RolePermissionsAuditTest extends IntegrationTestBase {

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

  /** Ação da troca do mapa de permissões (§7.2); é por ela que o teste filtra o log. */
  private static final String ACTION = "ROLE_PERMISSIONS_CHANGED";

  private static final String ROLES_PATH = "/api/v1/roles";

  @Test
  @DisplayName("PUT /roles/{code}/permissions grava ROLE_PERMISSIONS_CHANGED com before/after")
  void auditsRolePermissionsChanged() throws SQLException {
    long eventsBefore = countEvents();

    replaceOperadorPermissions(List.of("stock.adjust", "report.read"));

    assertThat(countEvents()).as("uma troca, um evento").isEqualTo(eventsBefore + 1);
    Event event = lastEvent();
    assertThat(event.entityType()).isEqualTo("ROLE");
    assertThat(event.entityId()).as("role não tem UUID: o alvo é o código, em details").isNull();
    assertThat(event.actorUsername()).isEqualTo(TestAdmin.USERNAME);

    JsonNode details = detailsOf(event);
    assertThat(details.path("role").asText()).isEqualTo("OPERADOR");
    assertThat(permissionsIn(details, "/before"))
        .as("o before vem da porta, em ordem alfabética")
        .containsExactlyElementsOf(sortedSeedPermissions());
    assertThat(permissionsIn(details, "/after")).containsExactly("report.read", "stock.adjust");
  }

  @Test
  @DisplayName("PUT que repete o conjunto atual responde 200 e não inventa evento")
  void repeatedSetDoesNotAudit() throws SQLException {
    // Mesmo conjunto atual, na ordem inversa da que a porta devolve: se a comparação usasse a ordem
    // do pedido, uma troca que não muda nada inventaria evento.
    List<String> current = operadorPermissions();
    List<String> reversed = new ArrayList<>(current);
    Collections.reverse(reversed);

    long eventsBefore = countEvents();
    Response response = putPermissions("OPERADOR", reversed, 200);

    assertThat(countEvents()).as("o no-op não inventa evento").isEqualTo(eventsBefore);
    assertThat(response.jsonPath().getList("permissions", String.class))
        .containsExactlyElementsOf(current);
  }

  /**
   * Devolve o mapa de OPERADOR ao seed depois de cada teste: os testes de repositório usam
   * {@code @TestTransaction} e assumem o catálogo como a migration o deixou.
   */
  @AfterEach
  void restoresOperadorSeed() {
    replaceOperadorPermissions(sortedSeedPermissions());
  }

  /** Eventos da ação no log agora; o log é append-only, então a troca que muda o mapa soma um. */
  private long countEvents() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select count(*) from audit_events where action = ?")) {
      statement.setString(1, ACTION);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }
  }

  /** Último evento da ação, na ordem de gravação: o que o PUT sob teste acabou de comitar. */
  private Event lastEvent() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select entity_type, entity_id, actor_username, details::text as details"
                    + " from audit_events where action = ? order by id desc limit 1")) {
      statement.setString(1, ACTION);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return new Event(
            resultSet.getString("entity_type"),
            resultSet.getString("entity_id"),
            resultSet.getString("actor_username"),
            resultSet.getString("details"));
      }
    }
  }

  /** {@code details} jsonb como JSON: o antes/depois em array é conferido código a código. */
  private static JsonNode detailsOf(Event event) {
    try {
      return new ObjectMapper().readTree(event.details());
    } catch (JsonProcessingException invalidJson) {
      throw new AssertionError("details não é JSON válido: " + event.details(), invalidJson);
    }
  }

  /** Códigos do array JSON no caminho informado (ex.: {@code /before}). */
  private static List<String> permissionsIn(JsonNode details, String path) {
    List<String> permissions = new ArrayList<>();
    details.at(path).forEach(node -> permissions.add(node.asText()));
    return permissions;
  }

  /** O seed de OPERADOR na ordem em que a porta devolve as permissões de uma role. */
  private static List<String> sortedSeedPermissions() {
    return OPERADOR_PERMISSIONS.stream().sorted().toList();
  }

  /** Permissões atuais de OPERADOR segundo o GET — o estado que o PUT seguinte precisa repetir. */
  private List<String> operadorPermissions() {
    return asAdmin()
        .when()
        .get(ROLES_PATH)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("[%d].permissions".formatted(OPERADOR_INDEX), String.class);
  }

  private void replaceOperadorPermissions(List<String> permissions) {
    putPermissions("OPERADOR", permissions, 200);
  }

  /** PUT do conjunto completo da role; espera o status informado e devolve a resposta. */
  private Response putPermissions(String code, List<String> permissions, int expectedStatus) {
    String body =
        permissions.stream()
            .map(permission -> "\"%s\"".formatted(permission))
            .collect(Collectors.joining(", ", "{\"permissions\": [", "]}"));
    return asAdmin()
        .contentType("application/json")
        .body(body)
        .when()
        .put(ROLES_PATH + "/{code}/permissions", code)
        .then()
        .statusCode(expectedStatus)
        .extract()
        .response();
  }

  /** Evento como o banco o guardou; {@code details} vem no formato textual do jsonb do PG. */
  private record Event(String entityType, String entityId, String actorUsername, String details) {}
}
