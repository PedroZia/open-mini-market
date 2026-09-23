package com.minimarket.users.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.IntegrationTestBase;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integridade da tabela {@code users} pela ótica do {@link UserRepository}: o índice único parcial
 * de username (só entre vivos) rejeita duplicidade e libera o reuso após soft delete, os timestamps
 * nascem preenchidos como {@code timestamptz} (UTC) e o lock otimista avança {@code version}.
 *
 * <p>PostgreSQL real via Dev Services; cada teste roda em transação revertida ao final
 * ({@code @TestTransaction}), então nada fica no banco. Cada teste com violação de constraint faz
 * uma única operação que falha — depois dela a transação está marcada para rollback.
 */
@QuarkusTest
class UserIntegrityTest extends IntegrationTestBase {

  /** SQLState de violação de unique constraint no PostgreSQL. */
  private static final String UNIQUE_VIOLATION = "23505";

  @Inject UserRepository userRepository;

  @Inject EntityManager entityManager;

  @Test
  @TestTransaction
  @DisplayName("username duplicado entre usuários vivos é rejeitado pelo índice único parcial")
  void rejectsDuplicateUsername() {
    userRepository.insert(new UserEntity("ana.silva", "hash", "Ana Silva"));
    entityManager.flush();

    userRepository.insert(new UserEntity("ana.silva", "hash", "Outra Ana"));

    assertThatThrownBy(() -> entityManager.flush())
        .isInstanceOf(PersistenceException.class)
        .satisfies(error -> assertThat(sqlStateOf(error)).isEqualTo(UNIQUE_VIOLATION));
  }

  @Test
  @TestTransaction
  @DisplayName("soft delete libera o username para um novo usuário")
  void allowsUsernameReuseAfterSoftDelete() {
    UserEntity deleted = userRepository.insert(new UserEntity("carla.dias", "hash", "Carla Dias"));
    entityManager.flush();
    entityManager.clear();

    userRepository.softDelete(deleted.getId());
    entityManager.flush();
    entityManager.clear();

    UserEntity recreated =
        userRepository.insert(new UserEntity("carla.dias", "hash", "Carla Nova"));
    entityManager.flush();
    entityManager.clear();

    assertThat(recreated.getId()).isNotEqualTo(deleted.getId());
    assertThat(userRepository.findById(recreated.getId()))
        .hasValueSatisfying(
            found -> {
              assertThat(found.getUsername()).isEqualTo("carla.dias");
              assertThat(found.getDisplayName()).isEqualTo("Carla Nova");
              assertThat(found.getDeletedAt()).isNull();
            });
    assertThat(userRepository.findById(deleted.getId()))
        .hasValueSatisfying(found -> assertThat(found.getDeletedAt()).isNotNull());
    assertThat(userRepository.search("carla.dias", null, 0, 10))
        .extracting(UserEntity::getId)
        .containsExactly(recreated.getId());
  }

  @Test
  @TestTransaction
  @DisplayName("created_at e updated_at são preenchidos no insert como timestamptz (UTC)")
  void fillsTimestampsInUtc() throws SQLException {
    // O banco guarda timestamptz com precisão de microssegundos, mas o relógio da JVM no Windows
    // tem 100 ns: truncar evita falso negativo quando o insert cai no mesmo microssegundo.
    Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
    UserEntity user = userRepository.insert(new UserEntity("diego.moura", "hash", "Diego Moura"));
    entityManager.flush();
    entityManager.clear();

    UserEntity reloaded = userRepository.findById(user.getId()).orElseThrow();
    Instant after = Instant.now().truncatedTo(ChronoUnit.MICROS);

    assertThat(reloaded.getCreatedAt()).isNotNull().isBetween(before, after);
    assertThat(reloaded.getUpdatedAt()).isNotNull().isBetween(before, after);
    assertThat(utcTimestampColumns()).isEqualTo(2);
  }

  @Test
  @TestTransaction
  @DisplayName("version incrementa a cada update do usuário")
  void incrementsVersionOnUpdate() {
    UserEntity user = userRepository.insert(new UserEntity("elisa.prado", "hash", "Elisa Prado"));
    entityManager.flush();
    entityManager.clear();

    UserEntity loaded = userRepository.findById(user.getId()).orElseThrow();
    long versionBefore = loaded.getVersion();
    loaded.setDisplayName("Elisa Prado Lima");

    userRepository.update(loaded);
    entityManager.flush();
    entityManager.clear();

    UserEntity reloaded = userRepository.findById(user.getId()).orElseThrow();
    assertThat(reloaded.getDisplayName()).isEqualTo("Elisa Prado Lima");
    assertThat(reloaded.getVersion()).isEqualTo(versionBefore + 1);
  }

  /** Quantas de {@code created_at}/{@code updated_at} são {@code timestamptz} no PostgreSQL. */
  private int utcTimestampColumns() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "select count(*) from information_schema.columns"
                    + " where table_schema = 'public' and table_name = 'users'"
                    + " and column_name in ('created_at', 'updated_at')"
                    + " and data_type = 'timestamp with time zone'")) {
      resultSet.next();
      return resultSet.getInt(1);
    }
  }

  /** Percorre a cadeia de causas até o SQLState da primeira {@link SQLException} encontrada. */
  private static String sqlStateOf(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException && sqlException.getSQLState() != null) {
        return sqlException.getSQLState();
      }
    }
    return null;
  }
}
