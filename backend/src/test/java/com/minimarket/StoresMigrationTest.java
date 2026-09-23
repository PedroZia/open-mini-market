package com.minimarket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StoresMigrationTest {

  @Inject DataSource dataSource;

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
      assertTrue(resultSet.next(), "loja MATRIZ deve existir");
      assertEquals("Matriz", resultSet.getString("name"));
      assertEquals("America/Sao_Paulo", resultSet.getString("timezone"));
      assertTrue(resultSet.getBoolean("allow_negative_stock"));
      assertEquals(new BigDecimal("100.00"), resultSet.getBigDecimal("max_discount_percent"));
    }
  }

  @Test
  @DisplayName("migrar de novo em banco já migrado não aplica nenhuma migration")
  void migrateAgainIsNoOp() {
    MigrateResult result = flyway.migrate();

    assertEquals(0, result.migrationsExecuted);
  }
}
