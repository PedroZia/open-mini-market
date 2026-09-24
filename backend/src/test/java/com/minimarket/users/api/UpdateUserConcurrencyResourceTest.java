package com.minimarket.users.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.support.TestAdmin;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Concorrência do último ADMIN na troca de papéis (passo 1012) contra PostgreSQL real (Dev
 * Services): dois {@code PUT /api/v1/users/{id}} simultâneos removendo o papel ADMIN dos dois
 * últimos ADMINs ativos não podem zerar os ADMINs.
 *
 * <p>O mesmo lock pessimista do 1008 (<code>RoleStore.lockActiveUserIdsWithRole</code>) serializa
 * as duas transações: quem perde a corrida reavalia a lista travada, já sem o ADMIN que o vencedor
 * tirou do papel, vê que sobrou um e recebe o 409 — sem retry e sem mudança de contrato.
 *
 * <p>Cada thread faz a própria requisição HTTP — transação e contexto próprios — disparada por um
 * {@link CountDownLatch} comum: sem {@code sleep} e sem {@code @Disabled}. O teste prova a
 * invariante no banco (sobra exatamente um ADMIN ativo, com o papel intacto) e a <em>contagem de
 * efeitos</em> (um 200, um 409, um {@code USER_UPDATED}), nunca a ordem em que as threads chegaram.
 *
 * <p>O request HTTP comita, então o {@code @AfterEach} apaga o que o teste criou na ordem das FKs
 * ({@code audit_events} antes de {@code user_roles}, que vem antes de {@code users}).
 */
@QuarkusTest
class UpdateUserConcurrencyResourceTest extends IntegrationTestBase {

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  /** Espera máxima das threads: o teste falha, nunca trava, se a corrida não terminar. */
  private static final long WORKER_TIMEOUT_SECONDS = 30;

  @Test
  @DisplayName(
      "dois PUTs simultâneos removendo ADMIN dos dois últimos ADMINs ativos: um 200 e um 409, e sobra exatamente um ADMIN ativo")
  void removesAdminRoleFromOneAdminUnderRace() throws Exception {
    String token = adminToken();
    String firstAdmin = createAdmin("papel.um." + SUFFIX, "Papel Um");
    String secondAdmin = createAdmin("papel.dois." + SUFFIX, "Papel Dois");
    // O ADMIN da fixture sai da contagem, como no teste do 1008: sem isso ele seria o ADMIN que
    // "sobra" e os dois PUTs passariam. O soft delete não derruba a sessão de quem faz as chamadas.
    softDeleteUser(TestAdmin.USERNAME);

    List<Response> responses =
        race(() -> removeAdminRole(firstAdmin, token), () -> removeAdminRole(secondAdmin, token));

    assertThat(statuses(responses))
        .as("o lock serializa: um remove o papel e o outro recebe o conflito do último ADMIN")
        .containsExactlyInAnyOrder(200, 409);
    assertThat(withStatus(responses, 200).jsonPath().getList("roles", String.class))
        .as("o vencedor fica sem o papel ADMIN")
        .doesNotContain("ADMIN");
    Response conflict = withStatus(responses, 409);
    assertThat(conflict.contentType()).contains("application/problem+json");
    assertThat(conflict.jsonPath().getString("code")).isEqualTo("CONFLICT");
    assertThat(conflict.jsonPath().getString("detail")).contains("último ADMIN ativo");

    // Invariantes finais: o perdedor mantém o papel, sobra exatamente um ADMIN ativo e um único
    // update foi auditado.
    assertThat(List.of(hasRole(firstAdmin, "ADMIN"), hasRole(secondAdmin, "ADMIN")))
        .as("exatamente um dos dois perdeu o papel")
        .containsExactlyInAnyOrder(false, true);
    assertThat(countActiveAdmins()).as("nunca zera os ADMINs").isEqualTo(1);
    assertThat(countUserUpdatedEvents(firstAdmin, secondAdmin))
        .as("um único USER_UPDATED, o do vencedor")
        .isEqualTo(1);
  }

  /**
   * Dispara as duas ações no mesmo instante — o latch é a largada comum — e devolve as duas
   * respostas, na ordem em que foram submetidas. Cada thread faz a própria requisição HTTP, com
   * transação e contexto próprios.
   */
  private static List<Response> race(Callable<Response> first, Callable<Response> second)
      throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<Response> firstResponse = workers.submit(awaiting(start, first));
      Future<Response> secondResponse = workers.submit(awaiting(start, second));
      start.countDown();
      return List.of(
          firstResponse.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS),
          secondResponse.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    } finally {
      workers.shutdown();
      assertThat(workers.awaitTermination(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("workers terminaram")
          .isTrue();
    }
  }

  /** Envolve a ação para ela só começar quando o latch da largada abrir. */
  private static Callable<Response> awaiting(CountDownLatch start, Callable<Response> action) {
    return () -> {
      start.await();
      return action.call();
    };
  }

  /** Cria o ADMIN da corrida pela API e devolve o id; a limpeza o remove pelo sufixo. */
  private String createAdmin(String username, String displayName) {
    return asAdmin()
        .contentType("application/json")
        .body(
            """
            {"username": "%s", "displayName": "%s", "password": "senha-secreta",
             "roleCodes": ["ADMIN"]}
            """
                .formatted(username, displayName))
        .when()
        .post("/api/v1/users")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("id");
  }

  /** PUT que substitui os papéis do alvo por nenhum: é o update que pode zerar os ADMINs. */
  private static Response removeAdminRole(String id, String token) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType("application/json")
        .body(
            """
            {"displayName": "Sem Papel", "roleCodes": []}
            """)
        .when()
        .put("/api/v1/users/{id}", id)
        .then()
        .extract()
        .response();
  }

  /** Status das duas respostas, para a contagem de efeitos: um vencedor e um conflito. */
  private static List<Integer> statuses(List<Response> responses) {
    return responses.stream().map(Response::statusCode).toList();
  }

  /** A resposta com o status esperado; o teste falha se não houver exatamente uma. */
  private static Response withStatus(List<Response> responses, int status) {
    return responses.stream()
        .filter(response -> response.statusCode() == status)
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "nenhuma resposta com status %d: %s".formatted(status, statuses(responses))));
  }

  /** O papel está atribuído ao usuário no banco? A fonte de verdade é {@code user_roles}. */
  private boolean hasRole(String id, String roleCode) throws SQLException {
    return queryInt(
            "select count(*) from user_roles ur join roles r on r.id = ur.role_id"
                + " where ur.user_id = ?::uuid and r.code = ?",
            id,
            roleCode)
        == 1;
  }

  /** ADMINs vivos e ACTIVE no banco: a corrida não pode zerar nem deixar dois. */
  private int countActiveAdmins() throws SQLException {
    return queryInt(
        "select count(*) from users u"
            + " join user_roles ur on ur.user_id = u.id"
            + " join roles r on r.id = ur.role_id"
            + " where r.code = 'ADMIN' and u.status = 'ACTIVE' and u.deleted_at is null");
  }

  /** Eventos de atualização dos dois alvos: um por update efetivado. */
  private int countUserUpdatedEvents(String firstId, String secondId) throws SQLException {
    return queryInt(
        "select count(*) from audit_events where action = 'USER_UPDATED'"
            + " and entity_id in (?::uuid, ?::uuid)",
        firstId,
        secondId);
  }

  private int queryInt(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  /** Soft delete direto no banco: atalho de fixture, sem passar pelo caso de uso de desativar. */
  private void softDeleteUser(String username) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("update users set deleted_at = now() where username = ?")) {
      statement.setString(1, username);
      statement.executeUpdate();
    }
  }

  /**
   * O request HTTP comita, então os usuários criados aqui são removidos ao fim do teste: os eventos
   * de auditoria saem antes (não há FK, mas a linha ficaria órfã) e a FK de {@code user_roles} é
   * {@code on delete restrict}, por isso as linhas de papel saem antes dos usuários.
   */
  @AfterEach
  void removeUsersCreatedByThisRun() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      delete(
          connection,
          "delete from audit_events where entity_id in"
              + " (select id from users where username like ?)",
          "%" + SUFFIX);
      delete(
          connection,
          "delete from user_roles where user_id in (select id from users where username like ?)",
          "%" + SUFFIX);
      delete(connection, "delete from users where username like ?", "%" + SUFFIX);
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
}
