package com.minimarket.auth.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserUseCase;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Negativas de permissão nas rotas de dinheiro da venda (passo 1011) contra PostgreSQL real (Dev
 * Services): um papel criado no teste, sem {@code sale.complete} e sem {@code payment.add},
 * autentica de verdade e recebe 403 {@code ACCESS_DENIED} nas duas rotas correspondentes — {@code
 * POST /api/v1/sales/{id}/complete} e {@code POST /api/v1/sales/{id}/payments}. O {@code
 * Idempotency-Key} e o id da venda nem chegam a ser avaliados: o 403 do porteiro vem antes do corpo
 * do método.
 *
 * <p><strong>Por que a fixture escreve no banco.</strong> Papel é catálogo vindo da migration V3 e
 * não existe caso de uso de criação — a administração (passo 114) só troca as permissões de um
 * papel que já existe. Então o papel do teste nasce pelas mesmas tabelas do seed ({@code roles} e
 * {@code role_permissions}), com uma permissão alheia às rotas ({@code product.read}) para o papel
 * carregar de verdade: o 403 é a falta das duas permissões, não um papel vazio por acidente. O
 * usuário nasce pelo caminho de aplicação ({@link CreateUserUseCase}), como nas demais fixtures de
 * API, e o login é o de verdade. O request HTTP commita, então o {@link
 * #removeRoleAndUserCreatedByThisRun()} apaga ao fim de cada teste o que ele criou, na ordem das
 * FKs ({@code on delete restrict}).
 */
@QuarkusTest
class PermissionDenialTest extends IntegrationTestBase {

  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  private static final String PASSWORD = "senha-secreta";

  private static final String AUTHORIZATION = "Authorization";

  private static final String ME_PATH = "/api/v1/auth/me";

  private static final String SALES_PATH = "/api/v1/sales/";

  /**
   * Corpo de um pagamento válido: a rota é lida antes do porteiro (JAX-RS desserializa e valida o
   * corpo antes de invocar o método), então precisa passar na validação para o 403 aparecer.
   */
  private static final String PAYMENT_BODY =
      """
      {"method": "PIX", "amount": 1.00}
      """;

  /** Papel do teste: sem {@code sale.complete} e sem {@code payment.add}, o alvo do 403. */
  private static final String ROLE_CODE = "RESTRITO." + SUFFIX;

  /** Permissão alheia às rotas testadas: prova que o papel carregou com permissão. */
  private static final String UNRELATED_PERMISSION = "product.read";

  /** Caso de uso da criação de usuário (passo 107): a fixture nasce por aqui, não pela API. */
  @Inject CreateUserUseCase createUserUseCase;

  @Test
  @DisplayName(
      "papel sem sale.complete e sem payment.add autenticado recebe 403 ACCESS_DENIED nas duas rotas")
  void deniesRoutesWithoutPermission() throws SQLException {
    String username = "negado.permissao." + SUFFIX;
    insertRole(ROLE_CODE, UNRELATED_PERMISSION);
    createUser(username, ROLE_CODE);
    String token = login(username);

    // Fixture sadia: o papel carregou (product.read) e as duas permissões realmente faltam — o 403
    // abaixo é a falta delas, não uma identidade sem permissão nenhuma.
    assertThat(permissionsOf(token))
        .as("permissões efetivas do papel criado no teste")
        .contains(UNRELATED_PERMISSION)
        .doesNotContain("sale.complete", "payment.add");

    // Venda inexistente de propósito: se a permissão passasse, o corpo responderia 404
    // SALE_NOT_FOUND (ou 400 pela chave de idempotência ausente) — o 403 prova o porteiro antes
    // do corpo do método.
    String missingSale = SALES_PATH + UUID.randomUUID();
    String completePath = missingSale + "/complete";
    String paymentsPath = missingSale + "/payments";

    assertDenied(post(token, completePath), completePath, "sale.complete");
    // /payments declara @Consumes(json): sem o Content-Type o JAX-RS responderia 415 antes do
    // porteiro — o corpo válido passa pela leitura e para no 403 do interceptor.
    assertDenied(postJson(token, paymentsPath, PAYMENT_BODY), paymentsPath, "payment.add");
  }

  /**
   * Requisição autenticada na rota: 403 {@code ACCESS_DENIED} em problem+json citando a permissão
   * exigida, como o teste do porteiro declara.
   */
  private static void assertDenied(Response response, String path, String permission) {
    assertThat(response.statusCode())
        .as("POST %s: sem a permissão o porteiro nega", path)
        .isEqualTo(403);
    assertThat(response.contentType())
        .as("POST %s: o 403 precisa ser problem+json", path)
        .contains("application/problem+json");
    assertThat(response.jsonPath().getString("code"))
        .as("POST %s: código do problema", path)
        .isEqualTo("ACCESS_DENIED");
    assertThat(response.jsonPath().getString("title")).isEqualTo("Acesso negado");
    assertThat(response.jsonPath().getInt("status")).isEqualTo(403);
    assertThat(response.jsonPath().getString("instance"))
        .as("POST %s: a resposta é do caminho pedido", path)
        .isEqualTo(path);
    assertThat(response.jsonPath().getString("detail"))
        .as("POST %s: cita a permissão exigida", path)
        .contains(permission);
    assertThat(response.jsonPath().getString("traceId")).isNotBlank();
  }

  /**
   * Cria o papel do teste no catálogo — a linha de {@code roles} como o seed da V3 a cria e o
   * vínculo com uma permissão do catálogo. Não existe caso de uso de criação de papel, então a
   * fixture escreve direto nas tabelas do RBAC.
   */
  private void insertRole(String roleCode, String permissionCode) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      execute(
          connection,
          "insert into roles (id, code, name, description, system)"
              + " values (uuidv7(), ?, ?, ?, false)",
          roleCode,
          "Papel restrito de teste",
          "Papel do passo 1011: sem sale.complete e sem payment.add");
      execute(
          connection,
          "insert into role_permissions (role_id, permission_id) select r.id, p.id from roles r"
              + " join permissions p on p.code = ? where r.code = ?",
          permissionCode,
          roleCode);
    }
  }

  /** Cria o usuário pelo caso de uso (passo 107) com o papel do teste. */
  private void createUser(String username, String roleCode) {
    createUserUseCase.execute(
        new CreateUserCommand(username, username, PASSWORD, List.of(roleCode)));
  }

  /** Login pela API (passo 205) e devolve o token em claro da sessão nova. */
  private static String login(String username) {
    return given()
        .contentType("application/json")
        .body(
            """
            {"username": "%s", "password": "%s"}
            """
                .formatted(username, PASSWORD))
        .when()
        .post("/api/v1/auth/login")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getString("token");
  }

  /** POST autenticado sem corpo (a rota de conclusão não declara {@code @Consumes}). */
  private static Response post(String token, String path) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .post(path)
        .then()
        .extract()
        .response();
  }

  /** POST autenticado com corpo JSON, para a rota que exige {@code Content-Type}. */
  private static Response postJson(String token, String path, String jsonBody) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .contentType("application/json")
        .body(jsonBody)
        .when()
        .post(path)
        .then()
        .extract()
        .response();
  }

  /** Permissões efetivas da sessão, como o {@code GET /auth/me} as devolve ao cliente. */
  private static List<String> permissionsOf(String token) {
    return given()
        .header(AUTHORIZATION, "Bearer " + token)
        .when()
        .get(ME_PATH)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("permissions", String.class);
  }

  /**
   * O request HTTP commita, então o que este teste cria é removido ao fim de cada um, na ordem das
   * FKs: os eventos de auditoria saem antes das sessões e usuários que eles referenciam (não há FK,
   * mas a linha ficaria órfã), as FKs de {@code auth_sessions}, {@code user_roles} e {@code
   * role_permissions} são {@code on delete restrict}, por isso o papel sai depois dos vínculos.
   */
  @AfterEach
  void removeRoleAndUserCreatedByThisRun() throws SQLException {
    String pattern = "%" + SUFFIX;
    try (Connection connection = dataSource.getConnection()) {
      execute(
          connection,
          "delete from audit_events where actor_user_id in"
              + " (select id from users where username like ?) or entity_id in"
              + " (select id from users where username like ?)",
          pattern,
          pattern);
      execute(
          connection,
          "delete from auth_sessions where user_id in (select id from users where username like ?)",
          pattern);
      execute(
          connection,
          "delete from user_roles where user_id in (select id from users where username like ?)",
          pattern);
      execute(connection, "delete from users where username like ?", pattern);
      execute(
          connection,
          "delete from role_permissions where role_id in"
              + " (select id from roles where code like ?)",
          pattern);
      execute(connection, "delete from roles where code like ?", pattern);
    }
  }

  private static void execute(Connection connection, String sql, String... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setString(index + 1, parameters[index]);
      }
      statement.executeUpdate();
    }
  }
}
