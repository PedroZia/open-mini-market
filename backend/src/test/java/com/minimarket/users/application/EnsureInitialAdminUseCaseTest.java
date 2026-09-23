package com.minimarket.users.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link EnsureInitialAdminUseCase} contra PostgreSQL real (Dev Services): é o aceite
 * do passo 115 — em banco limpo existe 1 ADMIN ativo com {@code mustChangePassword = true} — mais a
 * idempotência e a garantia de que o perfil de teste padrão não semeia nada. O caso de uso commita
 * de verdade (não é {@code @TestTransaction}), então o ADMIN criado é removido a cada teste: a FK
 * de {@code user_roles} é {@code on delete restrict} e o teste do passo 112 conta os ADMINs ativos.
 */
@QuarkusTest
class EnsureInitialAdminUseCaseTest extends IntegrationTestBase {

  private static final String PASSWORD = "senha-inicial-do-teste";

  @Inject EnsureInitialAdminUseCase useCase;

  @Inject RoleStore roleStore;

  @Inject PasswordHasher passwordHasher;

  /** Username que o inicializador usa por default; é o alvo do caso de uso no perfil de teste. */
  @ConfigProperty(name = "minimarket.security.admin.username")
  String username;

  /** No perfil de teste padrão não há senha inicial: é o que mantém o inicializador em silêncio. */
  @ConfigProperty(name = "minimarket.security.admin.initial-password")
  Optional<String> initialPassword;

  @Test
  @DisplayName("em banco limpo cria 1 ADMIN ativo com mustChangePassword=true e só o papel ADMIN")
  void createsInitialAdmin() throws SQLException {
    assertThat(roleStore.countActiveUsersWithRole("ADMIN"))
        .as("nenhum ADMIN ativo pode sobrar de outro teste para o cenário de banco limpo valer")
        .isZero();

    assertThat(useCase.execute(username, PASSWORD)).isTrue();

    assertThat(countUsers(username)).isEqualTo(1);
    assertThat(roleStore.countActiveUsersWithRole("ADMIN")).isEqualTo(1);
    String id = idOf(username);
    assertThat(columnOf(id, "status")).isEqualTo("ACTIVE");
    assertThat(columnOf(id, "must_change_password")).isEqualTo("t");
    assertThat(columnOf(id, "deleted_at")).isNull();
    assertThat(roleCodesOf(id)).containsExactly("ADMIN");
    assertThat(passwordHasher.verify(PASSWORD, columnOf(id, "password_hash"))).isTrue();
  }

  @Test
  @DisplayName("executar de novo com ADMIN ativo não cria segundo usuário nem troca a senha")
  void isIdempotent() throws SQLException {
    assertThat(useCase.execute(username, PASSWORD)).isTrue();
    String hashAfterFirstRun = columnOf(idOf(username), "password_hash");

    assertThat(useCase.execute(username, "outra-senha-inicial")).isFalse();

    assertThat(countUsers(username)).isEqualTo(1);
    assertThat(roleStore.countActiveUsersWithRole("ADMIN")).isEqualTo(1);
    assertThat(columnOf(idOf(username), "password_hash")).isEqualTo(hashAfterFirstRun);
  }

  @Test
  @DisplayName("perfil de teste padrão não tem senha inicial nem ADMIN semeado no startup")
  void defaultTestProfileDoesNotSeedAdmin() throws SQLException {
    assertThat(initialPassword).isEmpty();
    assertThat(countUsers(username)).isZero();
  }

  /** Limpa antes e depois: cada teste parte de banco sem o ADMIN inicial e não deixa rastro. */
  @BeforeEach
  @AfterEach
  void removeInitialAdmin() throws SQLException {
    deleteUser(username);
  }

  private String idOf(String username) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select id from users where username = ?")) {
      statement.setString(1, username);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).isTrue();
        return resultSet.getString(1);
      }
    }
  }

  private String columnOf(String id, String column) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select " + column + " from users where id = ?::uuid")) {
      statement.setString(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getString(1) : null;
      }
    }
  }

  private List<String> roleCodesOf(String id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select r.code from user_roles ur join roles r on r.id = ur.role_id"
                    + " where ur.user_id = ?::uuid order by r.code")) {
      statement.setString(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<String> codes = new ArrayList<>();
        while (resultSet.next()) {
          codes.add(resultSet.getString(1));
        }
        return codes;
      }
    }
  }

  private int countUsers(String username) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select count(*) from users where username = ?")) {
      statement.setString(1, username);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  /** A FK de {@code user_roles} é {@code on delete restrict}: os papéis saem antes do usuário. */
  private void deleteUser(String username) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "delete from user_roles where user_id in (select id from users where username = ?)")) {
        statement.setString(1, username);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement("delete from users where username = ?")) {
        statement.setString(1, username);
        statement.executeUpdate();
      }
    }
  }
}
