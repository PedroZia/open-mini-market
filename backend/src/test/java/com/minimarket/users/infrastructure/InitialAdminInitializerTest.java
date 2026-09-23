package com.minimarket.users.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.users.application.PasswordHasher;
import com.minimarket.users.application.RoleStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prova a fiação do {@link InitialAdminInitializer}: com a senha inicial configurada, o startup
 * cria o ADMIN (passo 115). O perfil do teste define username e senha — é o mesmo caminho de uma
 * variável de ambiente no ambiente novo — e o usuário semeado sai no {@link #removeSeededAdmin()},
 * porque o inicializador só roda no startup e o resto da suíte não pode encontrá-lo.
 */
@QuarkusTest
@TestProfile(InitialAdminInitializerTest.SeedProfile.class)
class InitialAdminInitializerTest extends IntegrationTestBase {

  private static final String PASSWORD = "senha-inicial-do-teste";

  /** Username só deste perfil: isola o ADMIN semeado dos demais testes. */
  private static final String USERNAME = "admin.semeado";

  @Inject RoleStore roleStore;

  @Inject PasswordHasher passwordHasher;

  @Test
  @DisplayName(
      "startup com senha inicial configurada cria 1 ADMIN ativo com mustChangePassword=true")
  void seedsInitialAdminOnStartup() throws SQLException {
    assertThat(countUsers(USERNAME)).isEqualTo(1);
    assertThat(roleStore.countActiveUsersWithRole("ADMIN")).isEqualTo(1);
    String id = idOf(USERNAME);
    assertThat(columnOf(id, "status")).isEqualTo("ACTIVE");
    assertThat(columnOf(id, "must_change_password")).isEqualTo("t");
    assertThat(columnOf(id, "deleted_at")).isNull();
    assertThat(roleCodesOf(id)).containsExactly("ADMIN");
    assertThat(passwordHasher.verify(PASSWORD, columnOf(id, "password_hash"))).isTrue();
  }

  @AfterEach
  void removeSeededAdmin() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "delete from user_roles where user_id in (select id from users where username = ?)")) {
        statement.setString(1, USERNAME);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement("delete from users where username = ?")) {
        statement.setString(1, USERNAME);
        statement.executeUpdate();
      }
    }
  }

  /** Configura o par username/senha do inicializador sem tocar nos arquivos de configuração. */
  public static class SeedProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "minimarket.security.admin.username", USERNAME,
          "minimarket.security.admin.initial-password", PASSWORD);
    }
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
}
