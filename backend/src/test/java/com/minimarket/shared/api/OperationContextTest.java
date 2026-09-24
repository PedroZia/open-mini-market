package com.minimarket.shared.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.auth.api.AuthResource;
import com.minimarket.auth.domain.TokenHasher;
import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserUseCase;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contexto de operação contra PostgreSQL real (Dev Services): login pela API, requisição ao {@link
 * TestOperationContextResource} e conferência dos campos que o filtro preencheu na requisição. O
 * request HTTP commita de verdade e o {@link #removeUsersCreatedByThisRun()} limpa sessões, papéis
 * e usuários ao fim de cada teste (as FKs são {@code on delete restrict}).
 */
@QuarkusTest
class OperationContextTest extends IntegrationTestBase {

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
  private static final String PASSWORD = "senha-secreta";
  private static final String CONTEXT_PATH = TestOperationContextResource.PATH;
  private static final String LOGIN_PATH = "/api/v1/auth/login";
  private static final String AUTHORIZATION = "Authorization";

  /** Domínio puro, sem estado e sem CDI (passo 203): o teste instancia o hash do token. */
  private final TokenHasher tokenHasher = new TokenHasher();

  /** Caso de uso da criação de usuário (passo 107): a fixture nasce por aqui, não pela API. */
  @Inject CreateUserUseCase createUserUseCase;

  @Test
  @DisplayName("sessão TUI chega no contexto com ator, sessão, loja, caixa, origem e correlação")
  void fillsContextForTuiSession() throws SQLException {
    String username = "ctx.tui." + SUFFIX;
    String userId = createUser(username);
    UUID cashRegisterId = UUID.randomUUID();
    String token = login(username, "TUI", cashRegisterId.toString());
    String requestId = UUID.randomUUID().toString();

    Response response = getContext(token, requestId);

    assertThat(response.statusCode()).isEqualTo(200);
    Session session = sessionOf(token);
    assertThat(response.jsonPath().getString("userId")).isEqualTo(userId);
    assertThat(response.jsonPath().getString("username")).isEqualTo(username);
    assertThat(response.jsonPath().getString("authSessionId")).isEqualTo(session.id());
    assertThat(response.jsonPath().getString("storeId")).isEqualTo(session.storeId());
    assertThat(response.jsonPath().getString("cashRegisterId"))
        .isEqualTo(cashRegisterId.toString());
    assertThat(response.jsonPath().getString("source")).isEqualTo("TUI");
    assertThat(response.jsonPath().getString("requestId")).isEqualTo(requestId);
    // O mesmo id vai no header da resposta: auditoria e correlação contam a mesma história.
    assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo(requestId);
    assertThat(InetAddress.ofLiteral(response.jsonPath().getString("ip")).isLoopbackAddress())
        .isTrue();
  }

  @Test
  @DisplayName("sessão WEB sem caixa chega no contexto com origem WEB e caixa nulo")
  void fillsContextForWebSessionWithoutCashRegister() throws SQLException {
    String username = "ctx.web." + SUFFIX;
    String userId = createUser(username);
    String token = login(username, "WEB", null);

    Response response = getContext(token, UUID.randomUUID().toString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.jsonPath().getString("userId")).isEqualTo(userId);
    assertThat(response.jsonPath().getString("username")).isEqualTo(username);
    assertThat(response.jsonPath().getString("source")).isEqualTo("WEB");
    assertThat(response.jsonPath().getString("cashRegisterId")).isNull();
    assertThat(response.jsonPath().getString("authSessionId")).isEqualTo(sessionOf(token).id());
  }

  @Test
  @DisplayName("sem X-Request-Id do cliente o contexto usa o id de correlação gerado pelo filtro")
  void reusesGeneratedRequestId() {
    String username = "ctx.correlacao." + SUFFIX;
    createUser(username);
    String token = login(username, "TUI", null);

    Response response = getContext(token, null);

    assertThat(response.statusCode()).isEqualTo(200);
    String echoed = response.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
    assertThat(echoed).isNotBlank();
    // Nada de um segundo UUID: o contexto reusa o id que o filtro de correlação já publicou.
    assertThat(response.jsonPath().getString("requestId")).isEqualTo(echoed);
  }

  @Test
  @DisplayName("rota de contexto sem token responde 401 problem+json")
  void rejectsMissingToken() {
    Response response = given().when().get(CONTEXT_PATH).then().extract().response();

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.contentType()).contains("application/problem+json");
    assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_CREDENTIALS");
    assertThat(response.jsonPath().getString("traceId")).isNotBlank();
  }

  /**
   * O request HTTP commita, então o que este teste cria é removido ao fim de cada um: as FKs são
   * {@code on delete restrict}, por isso sessões e papéis saem antes do usuário.
   */
  @AfterEach
  void removeUsersCreatedByThisRun() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "delete from auth_sessions where user_id in"
                  + " (select id from users where username like ?)")) {
        statement.setString(1, "%" + SUFFIX);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "delete from user_roles where user_id in"
                  + " (select id from users where username like ?)")) {
        statement.setString(1, "%" + SUFFIX);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement("delete from users where username like ?")) {
        statement.setString(1, "%" + SUFFIX);
        statement.executeUpdate();
      }
    }
  }

  /** Cria o usuário OPERADOR pelo caso de uso (passo 107) e devolve o id. */
  private String createUser(String username) {
    return createUserUseCase
        .execute(new CreateUserCommand(username, username, PASSWORD, List.of("OPERADOR")))
        .id()
        .toString();
  }

  /** Login pela API com o cliente informado; {@code cashRegisterId} nulo não vai no corpo. */
  private static String login(String username, String client, String cashRegisterId) {
    String body =
        cashRegisterId == null
            ? """
              {"username": "%s", "password": "%s"}
              """
                .formatted(username, PASSWORD)
            : """
              {"username": "%s", "password": "%s", "cashRegisterId": "%s"}
              """
                .formatted(username, PASSWORD, cashRegisterId);
    return given()
        .contentType("application/json")
        .header(AuthResource.CLIENT_HEADER, client)
        .body(body)
        .when()
        .post(LOGIN_PATH)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getString("token");
  }

  /**
   * GET no recurso de contexto com o token e o {@code X-Request-Id} informados (nulos omitidos).
   */
  private static Response getContext(String token, String requestId) {
    RequestSpecification request = given().header(AUTHORIZATION, "Bearer " + token);
    if (requestId != null) {
      request = request.header(RequestIdFilter.REQUEST_ID_HEADER, requestId);
    }
    return request.when().get(CONTEXT_PATH).then().extract().response();
  }

  /** Id e loja da sessão do token, direto do banco: a fonte de verdade do contexto. */
  private Session sessionOf(String token) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select id, store_id from auth_sessions where token_hash = ?")) {
      statement.setString(1, tokenHasher.hash(token));
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).isTrue();
        return new Session(resultSet.getString("id"), resultSet.getString("store_id"));
      }
    }
  }

  /** Id e loja da sessão autenticada, como o banco os guardou. */
  private record Session(String id, String storeId) {}
}
