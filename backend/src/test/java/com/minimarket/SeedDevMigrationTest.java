package com.minimarket;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SeedDevMigrationTest extends IntegrationTestBase {

  private static final String SEED_RESOURCE = "db/seed-dev/R__seed_dev.sql";

  @Test
  @DisplayName("o seed de desenvolvimento é aplicado pelo Flyway sem erro")
  void seedIsApplied() throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select success from flyway_schema_history where script = ?")) {
      statement.setString(1, SEED_RESOURCE);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next())
            .as("%s deve estar no histórico do Flyway", SEED_RESOURCE)
            .isTrue();
        assertThat(resultSet.getBoolean("success")).isTrue();
      }
    }
  }

  @Test
  @DisplayName("reexecutar o seed não duplica a loja MATRIZ")
  void rerunningSeedDoesNotDuplicate() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        // Apaga a loja dentro da transação (rollback no fim) para provar que o seed a recria.
        statement.executeUpdate("delete from stores where code = 'MATRIZ'");

        statement.execute(seedSql());
        statement.execute(seedSql());

        try (ResultSet resultSet =
            statement.executeQuery("select count(*) from stores where code = 'MATRIZ'")) {
          assertThat(resultSet.next()).isTrue();
          assertThat(resultSet.getInt(1))
              .as("duas execuções do seed devem deixar exatamente uma loja MATRIZ")
              .isEqualTo(1);
        }
      } finally {
        connection.rollback();
      }
    }
  }

  private static String seedSql() throws IOException {
    try (InputStream seed =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(SEED_RESOURCE)) {
      assertThat(seed).as("seed %s deve estar no classpath", SEED_RESOURCE).isNotNull();
      return new String(seed.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
