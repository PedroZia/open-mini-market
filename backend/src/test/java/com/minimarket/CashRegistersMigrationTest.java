package com.minimarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.junit.QuarkusTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CashRegistersMigrationTest extends IntegrationTestBase {

  @Test
  @DisplayName("a migration V11 cria a tabela cash_registers com as colunas do plano")
  void appliesFromScratch() throws Exception {
    Set<String> columns = new HashSet<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "select column_name from information_schema.columns"
                    + " where table_schema = 'public' and table_name = 'cash_registers'")) {
      while (resultSet.next()) {
        columns.add(resultSet.getString("column_name"));
      }
    }

    assertThat(columns)
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                "id", "store_id", "code", "name", "active", "created_at", "updated_at", "version"));
  }

  @Test
  @DisplayName("a migration V11 semeia o caixa CAIXA-01 ativo na loja MATRIZ")
  void seedsCashRegister() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "select r.name, r.active from cash_registers r"
                    + " join stores s on s.id = r.store_id"
                    + " where r.code = 'CAIXA-01' and s.code = 'MATRIZ'")) {
      assertThat(resultSet.next()).as("caixa CAIXA-01 da loja MATRIZ deve existir").isTrue();
      assertThat(resultSet.getString("name")).isEqualTo("Caixa 1");
      assertThat(resultSet.getBoolean("active")).as("caixa semeado deve estar ativo").isTrue();
      assertThat(resultSet.next()).as("não deve haver CAIXA-01 duplicado").isFalse();
    }
  }

  @Test
  @DisplayName("código de caixa duplicado na mesma loja falha com SQLState 23505")
  void rejectsDuplicateCodeInTheSameStore() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        insertRegister(connection, "CAIXA-02");
        assertThatThrownBy(() -> insertRegister(connection, "CAIXA-02"))
            .isInstanceOf(SQLException.class)
            .satisfies(
                error ->
                    assertThat(((SQLException) error).getSQLState())
                        .as("violação de unicidade")
                        .isEqualTo("23505"));
      } finally {
        connection.rollback();
      }
    }
  }

  private void insertRegister(Connection connection, String code) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "insert into cash_registers (id, store_id, code, name)"
                + " values (uuidv7(), (select id from stores where code = 'MATRIZ'), ?,"
                + " 'Caixa teste')")) {
      statement.setString(1, code);
      statement.executeUpdate();
    }
  }
}
