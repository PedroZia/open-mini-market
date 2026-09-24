package com.minimarket.support;

import static io.restassured.RestAssured.given;

import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserUseCase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;

/**
 * ADMIN real para os testes de API das Fases 1–2 (passo 307a): desde que a política global fechou a
 * janela da Fase 1, criar usuário pela API exige token com permissão, então os testes precisam de
 * um ator autenticado de verdade.
 *
 * <p>O usuário é criado pelo <em>caminho de aplicação</em> ({@link CreateUserUseCase}) e não pela
 * API: aqui ele é fixture, não o alvo do teste — quem checa a permissão é a API, e quem prova isso
 * são os testes de {@code UsersResourceTest}/{@code RolesResourceTest}. O login, ao contrário, é o
 * de verdade ({@code POST /api/v1/auth/login}), para o token ser o mesmo que o cliente usa.
 *
 * <p>O usuário vive só durante o teste: {@link #remove()} apaga eventos, sessões, papéis e o
 * usuário no fim de cada um (as FKs de {@code auth_sessions} e {@code user_roles} são {@code on
 * delete restrict}), porque os testes de repositório assumem as tabelas como as encontraram.
 */
@ApplicationScoped
public class TestAdmin {

  /** Username da fixture; fora dos sufixos dos testes para não colidir com nenhum deles. */
  public static final String USERNAME = "admin.testes";

  /** Senha da fixture: o login passa pelo mesmo Argon2id e pela mesma política da aplicação. */
  private static final String PASSWORD = "senha-do-admin-testes";

  /** Papel que traz as permissões de administração (user.read/write, role.write e revoke). */
  private static final String ADMIN_ROLE = "ADMIN";

  @Inject CreateUserUseCase createUserUseCase;

  @Inject DataSource dataSource;

  /**
   * Cria o ADMIN da fixture se ele ainda não existir. Idempotente por {@code
   * USERNAME_ALREADY_EXISTS}: um {@code @AfterEach} que precise do token depois de um teste que já
   * o usou encontra o usuário no lugar.
   */
  public void ensureExists() {
    try {
      createUserUseCase.execute(
          new CreateUserCommand(USERNAME, "Admin de Testes", PASSWORD, List.of(ADMIN_ROLE)));
    } catch (ConflictException alreadyExists) {
      if (alreadyExists.code() != ErrorCode.USERNAME_ALREADY_EXISTS) {
        throw alreadyExists;
      }
    }
  }

  /**
   * Login real na API e devolve o token em claro da sessão nova (que morre no {@link #remove()}).
   */
  public String login() {
    ensureExists();
    return given()
        .contentType("application/json")
        .body(
            """
            {"username": "%s", "password": "%s"}
            """
                .formatted(USERNAME, PASSWORD))
        .when()
        .post("/api/v1/auth/login")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getString("token");
  }

  /**
   * Apaga o que o teste comitou para a fixture: os eventos de auditoria do próprio login, as
   * sessões, os papéis e o usuário. Sem linha nenhuma (o teste não chegou a usar o token), é no-op.
   */
  public void remove() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      execute(
          connection,
          "delete from audit_events where actor_user_id in"
              + " (select id from users where username = ?) or entity_id in"
              + " (select id from users where username = ?)",
          USERNAME,
          USERNAME);
      execute(
          connection,
          "delete from auth_sessions where user_id in (select id from users where username = ?)",
          USERNAME);
      execute(
          connection,
          "delete from user_roles where user_id in (select id from users where username = ?)",
          USERNAME);
      execute(connection, "delete from users where username = ?", USERNAME);
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
