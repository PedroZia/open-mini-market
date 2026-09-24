package com.minimarket.customers.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.support.TestAdmin;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Auditoria do cadastro de clientes (passo 502b) contra PostgreSQL real (Dev Services): as três
 * operações da API — criar, editar e desativar — são conferidas pelo par {@code entity_id} + {@code
 * action} (§7.2), nunca por contagem global, com o antes/depois extraído do jsonb. Como no {@code
 * CatalogPermissionsAuditTest}, o que não grava nada (400, 404 e o no-op do PUT) não pode inventar
 * evento.
 *
 * <p>A fixture é criada pela própria API (é o caminho auditado); o {@link
 * #removeRowsCreatedByThisTest()} apaga os eventos e os clientes do sufixo desta classe no fim de
 * cada teste, e os eventos do ADMIN já saem pelo {@code TestAdmin.remove}.
 */
@QuarkusTest
class CustomersAuditTest extends IntegrationTestBase {

  /** Sufixo desta classe: nomes nascem e morrem com ele. */
  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  private static final String AUTHORIZATION = "Authorization";
  private static final String PATH = "/api/v1/customers";
  private static final String ANA_CPF = "39053344705";
  private static final String ANA_MASKED_CPF = "390.533.447-05";
  private static final String BRUNO_MASKED_CPF = "222.333.444-05";
  private static final String BRUNO_CPF = "22233344405";

  @Test
  @DisplayName("criar, editar e desativar gravam CUSTOMER_CREATED, UPDATED e DISABLED no entity_id")
  void auditsEveryCustomerOperation() throws SQLException {
    Response created =
        post(
            """
            {"name": "%s", "taxId": "%s", "phone": "(11) 91234-5678", "notes": "vizinho"}
            """
                .formatted(name("Ana Souza"), ANA_MASKED_CPF));

    assertThat(created.statusCode()).isEqualTo(201);
    UUID id = UUID.fromString(created.jsonPath().getString("id"));

    AuditEvent createdEvent = eventOf(id, "CUSTOMER_CREATED");

    assertCustomerEvent(createdEvent, id);
    JsonPath createdDetails = JsonPath.from(createdEvent.details());

    assertThat(createdDetails.getString("name")).isEqualTo(name("Ana Souza"));
    assertThat(createdDetails.getString("taxId"))
        .as("o evento guarda o CPF como o banco o guardou")
        .isEqualTo(ANA_CPF);

    Response updated =
        put(
            id,
            """
            {"name": "%s", "taxId": "%s", "phone": "(21) 98765-4321", "email": "ana.lima@exemplo.com",
             "notes": "mudou"}
            """
                .formatted(name("Ana Souza Lima"), BRUNO_MASKED_CPF));

    assertThat(updated.statusCode()).isEqualTo(200);

    AuditEvent updatedEvent = eventOf(id, "CUSTOMER_UPDATED");

    assertCustomerEvent(updatedEvent, id);
    JsonPath updatedDetails = JsonPath.from(updatedEvent.details());

    assertThat(updatedDetails.getString("before.name")).isEqualTo(name("Ana Souza"));
    assertThat(updatedDetails.getString("before.taxId")).isEqualTo(ANA_CPF);
    assertThat(updatedDetails.getString("before.phone")).isEqualTo("11912345678");
    assertThat(updatedDetails.getString("before.notes")).isEqualTo("vizinho");
    assertThat(updatedDetails.getString("after.name")).isEqualTo(name("Ana Souza Lima"));
    assertThat(updatedDetails.getString("after.taxId")).isEqualTo(BRUNO_CPF);
    assertThat(updatedDetails.getString("after.phone")).isEqualTo("21987654321");
    assertThat(updatedDetails.getString("after.email")).isEqualTo("ana.lima@exemplo.com");
    assertThat(updatedDetails.getString("after.notes")).isEqualTo("mudou");

    // Editar sem mudança efetiva é no-op: responde 200 e não inventa evento para o alvo.
    int eventsBefore = eventCount(id);

    assertThat(
            put(
                    id,
                    """
            {"name": "%s", "taxId": "%s", "phone": "(21) 98765-4321", "email": "ana.lima@exemplo.com",
             "notes": "mudou"}
            """
                        .formatted(name("Ana Souza Lima"), BRUNO_MASKED_CPF))
                .statusCode())
        .isEqualTo(200);
    assertThat(eventCount(id)).as("o no-op não inventa evento").isEqualTo(eventsBefore);
    assertThat(eventCount(id, "CUSTOMER_UPDATED")).as("uma edição de fato, um evento").isEqualTo(1);

    assertThat(postStatus(id, "disable").statusCode()).isEqualTo(200);

    AuditEvent disabledEvent = eventOf(id, "CUSTOMER_DISABLED");

    assertCustomerEvent(disabledEvent, id);
    JsonPath disabledDetails = JsonPath.from(disabledEvent.details());

    assertThat(disabledDetails.getString("before.active")).isEqualTo("true");
    assertThat(disabledDetails.getString("after.active")).isEqualTo("false");
  }

  @Test
  @DisplayName("operação recusada (400, 409 ou 404) não grava evento para o alvo")
  void doesNotAuditRejectedOperations() throws SQLException {
    Response created =
        post(
            """
            {"name": "%s", "taxId": "%s"}
            """
                .formatted(name("Ana Souza"), ANA_MASKED_CPF));

    assertThat(created.statusCode()).isEqualTo(201);
    UUID id = UUID.fromString(created.jsonPath().getString("id"));

    // CPF inválido: 400 antes de qualquer gravação.
    assertStatus(post(createBody(name("Ana Clara"), "11144477736")), 400);

    // CPF já tomado: 409 na checagem do caso de uso.
    assertStatus(post(createBody(name("Ana Clara"), ANA_CPF)), 409);

    // Id desconhecido e cliente já desativado: 404 sem evento.
    UUID unknown = UUID.randomUUID();

    assertStatus(put(unknown, createBody(name("Fantasma"), null)), 404);
    assertStatus(postStatus(id, "disable"), 200);
    assertStatus(postStatus(id, "disable"), 404);

    assertThat(eventCount(unknown)).as("o 404 de id desconhecido não audita").isZero();
    assertThat(eventCount(id, "CUSTOMER_DISABLED"))
        .as("uma desativação de fato, um evento")
        .isEqualTo(1);
    assertThat(eventCount(id)).as("o 409, o 400 e o 404 de desativado não auditaram").isEqualTo(2);
  }

  /** Campos comuns dos eventos de cliente: alvo, origem de requisição autenticada e ator ADMIN. */
  private static void assertCustomerEvent(AuditEvent event, UUID id) {
    assertThat(event.entityType()).isEqualTo("CUSTOMER");
    assertThat(event.entityId()).isEqualTo(id.toString());
    assertThat(event.source())
        .as("evento da requisição autenticada, não de sistema")
        .isNotEqualTo("SYSTEM");
    assertThat(event.actorUsername()).isEqualTo(TestAdmin.USERNAME);
  }

  /** Status esperado da resposta, com o corpo na mensagem para a falha ser autoexplicativa. */
  private static void assertStatus(Response response, int status) {
    assertThat(response.statusCode()).as("resposta: %s", response.asString()).isEqualTo(status);
  }

  /** Corpo mínimo do cadastro; CPF nulo vira {@code null} no JSON — o mesmo que omiti-lo. */
  private static String createBody(String name, String taxId) {
    return """
        {"name": "%s", "taxId": %s}
        """
        .formatted(name, taxId == null ? "null" : "\"" + taxId + "\"");
  }

  /** Nome de cliente com o sufixo da classe, para a limpeza no fim do teste. */
  private static String name(String base) {
    return base + "-" + SUFFIX;
  }

  private Response post(String body) {
    return given()
        .header(AUTHORIZATION, "Bearer " + adminToken())
        .contentType("application/json")
        .body(body)
        .when()
        .post(PATH)
        .then()
        .extract()
        .response();
  }

  private Response put(UUID id, String body) {
    RequestSpecification request =
        given()
            .header(AUTHORIZATION, "Bearer " + adminToken())
            .contentType("application/json")
            .body(body);
    return request.when().put(PATH + "/" + id).then().extract().response();
  }

  private Response postStatus(UUID id, String status) {
    return given()
        .header(AUTHORIZATION, "Bearer " + adminToken())
        .when()
        .post(PATH + "/" + id + "/" + status)
        .then()
        .extract()
        .response();
  }

  /**
   * Evento da ação pelo alvo, com {@code details} no texto do jsonb; falha se houver zero ou mais
   * de um evento para o par — a conferência é sempre por {@code entity_id} + {@code action}.
   */
  private AuditEvent eventOf(UUID entityId, String action) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select entity_type, entity_id::text as entity_id, source, actor_username, reason,"
                    + " details::text as details from audit_events"
                    + " where action = ? and entity_id = ?::uuid")) {
      statement.setString(1, action);
      statement.setString(2, entityId.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("evento %s do alvo %s", action, entityId).isTrue();
        AuditEvent event =
            new AuditEvent(
                resultSet.getString("entity_type"),
                resultSet.getString("entity_id"),
                resultSet.getString("source"),
                resultSet.getString("actor_username"),
                resultSet.getString("reason"),
                resultSet.getString("details"));
        assertThat(resultSet.next()).as("uma operação, um evento %s", action).isFalse();
        return event;
      }
    }
  }

  /** Quantos eventos o alvo tem, de qualquer ação — o no-op compara antes e depois. */
  private int eventCount(UUID entityId) throws SQLException {
    return eventCount(entityId, null);
  }

  /** Quantos eventos da ação o alvo tem; ação nula conta todas as ações. */
  private int eventCount(UUID entityId, String action) throws SQLException {
    String sql =
        action == null
            ? "select count(*) from audit_events where entity_id = ?::uuid"
            : "select count(*) from audit_events where action = ? and entity_id = ?::uuid";
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      int parameter = 1;
      if (action != null) {
        statement.setString(parameter++, action);
      }
      statement.setString(parameter, entityId.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  /**
   * O request HTTP commita: some ao fim de cada teste o que esta classe criou — os eventos que
   * apontam para os clientes do sufixo antes dos próprios clientes.
   */
  @AfterEach
  void removeRowsCreatedByThisTest() throws SQLException {
    String suffixLike = "%" + SUFFIX;
    try (Connection connection = dataSource.getConnection()) {
      delete(
          connection,
          "delete from audit_events where entity_id in (select id from customers where name like ?)",
          suffixLike);
      delete(connection, "delete from customers where name like ?", suffixLike);
    }
  }

  private static void delete(Connection connection, String sql, String... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setString(index + 1, parameters[index]);
      }
      statement.executeUpdate();
    }
  }

  /** Linha de {@code audit_events} com o {@code details} no texto do jsonb, pronto para o GPath. */
  private record AuditEvent(
      String entityType,
      String entityId,
      String source,
      String actorUsername,
      String reason,
      String details) {}
}
