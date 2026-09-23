package com.minimarket;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StoresMigrationTest extends IntegrationTestBase {

  @Inject Flyway flyway;

  @Test
  @DisplayName("a migration V1 cria a tabela e semeia a loja MATRIZ")
  void seedsMatrizStore() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "select code, name, timezone, allow_negative_stock, max_discount_percent"
                    + " from stores where code = 'MATRIZ'")) {
      assertThat(resultSet.next()).as("loja MATRIZ deve existir").isTrue();
      assertThat(resultSet.getString("name")).isEqualTo("Matriz");
      assertThat(resultSet.getString("timezone")).isEqualTo("America/Sao_Paulo");
      assertThat(resultSet.getBoolean("allow_negative_stock")).isTrue();
      assertThat(resultSet.getBigDecimal("max_discount_percent"))
          .isEqualByComparingTo(new BigDecimal("100.00"));
    }
  }

  @Test
  @DisplayName("migrar de novo em banco já migrado não aplica nenhuma migration")
  void migrateAgainIsNoOp() {
    MigrateResult result = flyway.migrate();

    assertThat(result.migrationsExecuted).isZero();
  }
}
