package com.minimarket.shared.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.users.application.CreateUserCommand;
import com.minimarket.users.application.CreateUserUseCase;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração da limpeza de idempotência contra PostgreSQL real (Dev Services) — critério de aceite
 * do passo 1004: a chave vencida é removida e a válida permanece.
 *
 * <p>Sem {@code @TestTransaction}: as chaves entram por JDBC e a limpeza comita de verdade, como no
 * job diário. O instante fixo é de propósito no passado em relação ao relógio da máquina — a chave
 * "válida" (que o relógio real já consideraria vencida) só sobrevive porque o corte é o relógio
 * injetado; se o caso de uso usasse o relógio do sistema, ela também sairia e o teste falharia. O
 * {@link Clock} fica no <em>contextual instance</em> (o {@code ClientProxy} não delega campos): daí
 * o {@code ClientProxy.unwrap} antes de escrever.
 *
 * <p>A fixture (o usuário da FK) nasce pelo caso de uso e o {@code @AfterEach} a remove na ordem
 * que as FKs exigem: chaves → eventos → papéis → usuário.
 */
@QuarkusTest
class PurgeExpiredIdempotencyKeysIntegrationTest extends IntegrationTestBase {

  private static final Instant NOW = Instant.parse("2026-01-15T03:00:00Z");
  private static final String KEY_PREFIX = "limpeza.idempotencia.";
  private static final String EXPIRED_KEY = KEY_PREFIX + "vencida";
  private static final String ON_CUTOFF_KEY = KEY_PREFIX + "no-corte";
  private static final String VALID_KEY = KEY_PREFIX + "valida";
  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
  private static final String USERNAME = "idempotencia.limpeza." + SUFFIX;
  private static final String PASSWORD = "senha-secreta";

  @Inject PurgeExpiredIdempotencyKeysUseCase purgeExpiredIdempotencyKeysUseCase;

  @Inject CreateUserUseCase createUserUseCase;

  private UUID userId;

  @Test
  @DisplayName("remove as chaves vencidas (inclusive no instante do corte) e preserva a válida")
  void removesExpiredKeyOnly() throws SQLException {
    userId =
        createUserUseCase
            .execute(
                new CreateUserCommand(
                    USERNAME, "Operador da limpeza", PASSWORD, List.of("OPERADOR")))
            .id();
    insertKey(EXPIRED_KEY, NOW.minusSeconds(60));
    insertKey(ON_CUTOFF_KEY, NOW);
    insertKey(VALID_KEY, NOW.plusSeconds(3600));
    ClientProxy.unwrap(purgeExpiredIdempotencyKeysUseCase).clock = Clock.fixed(NOW, ZoneOffset.UTC);

    int removed = purgeExpiredIdempotencyKeysUseCase.execute();

    assertThat(removed).as("a vencida e a que venceu exatamente no corte").isEqualTo(2);
    assertThat(keys()).as("a válida continua na tabela").containsExactly(VALID_KEY);
  }

  /** Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}. */
  @AfterEach
  void removeCommittedRows() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      execute(connection, "delete from idempotency_keys where key like ?", KEY_PREFIX + "%");
      execute(
          connection,
          "delete from audit_events where actor_user_id in (select id from users where username ="
              + " ?) or entity_id in (select id from users where username = ?)",
          USERNAME,
          USERNAME);
      execute(
          connection,
          "delete from user_roles where user_id in (select id from users where username = ?)",
          USERNAME);
      execute(connection, "delete from users where username = ?", USERNAME);
    }
  }

  /** Chave de idempotência do usuário da fixture, com expiração fixa — como o serviço a gravou. */
  private void insertKey(String key, Instant expiresAt) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "insert into idempotency_keys (key, user_id, method, path, request_hash, status_code,"
                    + " response_body, expires_at) values (?, ?, 'POST',"
                    + " '/api/v1/cash-registers/x/open', 'hash-da-limpeza', 201, '\"ok\"'::jsonb,"
                    + " ?::timestamptz)")) {
      statement.setString(1, key);
      statement.setObject(2, userId);
      statement.setString(3, expiresAt.toString());
      statement.executeUpdate();
    }
  }

  /** Chaves do prefixo do teste como o banco as guardou, em ordem de {@code key}. */
  private List<String> keys() throws SQLException {
    List<String> keys = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select key from idempotency_keys where key like ? order by key")) {
      statement.setString(1, KEY_PREFIX + "%");
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          keys.add(resultSet.getString(1));
        }
      }
    }
    return keys;
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
