package com.minimarket.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Auditoria do {@link CreateProductUseCase} contra PostgreSQL real (Dev Services): sem requisição
 * HTTP não existe request context ativo, então o evento nasce como operação de sistema (ator nulo,
 * origem {@code SYSTEM}) — o mesmo caminho do ADMIN inicial do passo 115. O caso de uso commita de
 * verdade (não é {@code @TestTransaction}), então o produto e o evento criados são removidos ao fim
 * do teste; a leitura é por SQL, com o banco como fonte de verdade do que foi gravado.
 */
@QuarkusTest
class CreateProductAuditTest extends IntegrationTestBase {

  private static final String BARCODE = "7891000000017";
  private static final String INTERNAL_CODE = "00042";
  private static final String NAME = "Arroz 5kg";

  @Inject CreateProductUseCase useCase;

  /** Alvos criados por este teste, para a limpeza (o caso de uso commita). */
  private final List<UUID> createdProducts = new ArrayList<>();

  @Test
  @DisplayName(
      "PRODUCT_CREATED grava a linha de auditoria na mesma transação, como operação SYSTEM")
  void recordsProductCreated() throws SQLException {
    CreateProductResult result =
        useCase.execute(
            new CreateProductCommand(
                NAME,
                BARCODE,
                INTERNAL_CODE,
                "grão longo",
                null,
                "UN",
                new BigDecimal("24.90"),
                new BigDecimal("1.000")));
    createdProducts.add(result.id());

    Event event = eventOf(result.id());
    assertThat(event.entityId()).isEqualTo(result.id().toString());
    assertThat(event.action()).isEqualTo("PRODUCT_CREATED");
    assertThat(event.entityType()).isEqualTo("PRODUCT");
    assertThat(event.source()).as("sem request HTTP o evento é de sistema").isEqualTo("SYSTEM");
    assertThat(event.actorUserId()).isNull();
    assertThat(event.storeId()).as("fora de request não há loja no contexto").isNull();
    assertThat(event.detailsName()).isEqualTo(NAME);
    assertThat(event.detailsBarcode()).isEqualTo(BARCODE);
    assertThat(event.detailsInternalCode())
        .as("o código interno da etiqueta entra nos details (passo 1104d)")
        .isEqualTo(INTERNAL_CODE);
    assertThat(event.detailsPrice()).isEqualTo("24.90");

    Product product = productOf(result.id());
    assertThat(product.barcode()).isEqualTo(BARCODE);
    assertThat(product.internalCode()).isEqualTo(INTERNAL_CODE);
    assertThat(product.unit()).isEqualTo("UN");
    assertThat(product.price()).isEqualTo("24.90");
    assertThat(product.active()).as("produto nasce ativo").isTrue();
    assertThat(product.deletedAt()).as("produto nasce sem deleted_at").isNull();
  }

  /**
   * O log é append-only para a aplicação; o teste remove o que ele mesmo comitou para não sujar as
   * execuções seguintes. O produto sai junto: sem ele o audit_events fica sem o alvo.
   */
  @AfterEach
  void removeRowsCreatedByThisTest() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      deleteByIds(connection, "delete from audit_events where entity_id = ?");
      deleteByIds(connection, "delete from products where id = ?");
    }
    createdProducts.clear();
  }

  private void deleteByIds(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (UUID id : createdProducts) {
        statement.setObject(1, id);
        statement.executeUpdate();
      }
    }
  }

  /** Evento como o banco o guardou; {@code details} vem extraído por chave do jsonb. */
  private Event eventOf(UUID entityId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select entity_id, action, entity_type, source, actor_user_id, store_id,"
                    + " details->>'name' as name, details->>'barcode' as barcode,"
                    + " details->>'internalCode' as internal_code, details->>'price' as price"
                    + " from audit_events where entity_id = ?")) {
      statement.setObject(1, entityId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("evento gravado para o produto %s", entityId).isTrue();
        return new Event(
            resultSet.getString("entity_id"),
            resultSet.getString("action"),
            resultSet.getString("entity_type"),
            resultSet.getString("source"),
            resultSet.getString("actor_user_id"),
            resultSet.getString("store_id"),
            resultSet.getString("name"),
            resultSet.getString("barcode"),
            resultSet.getString("internal_code"),
            resultSet.getString("price"));
      }
    }
  }

  /** Produto como o banco o guardou; preço no formato textual do numeric(14,2). */
  private Product productOf(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select barcode, internal_code, unit, price::text as price, active, deleted_at"
                    + " from products where id = ?")) {
      statement.setObject(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("produto gravado %s", id).isTrue();
        return new Product(
            resultSet.getString("barcode"),
            resultSet.getString("internal_code"),
            resultSet.getString("unit"),
            resultSet.getString("price"),
            resultSet.getBoolean("active"),
            resultSet.getObject("deleted_at"));
      }
    }
  }

  /** Linha de {@code audit_events} como o banco a guardou. */
  private record Event(
      String entityId,
      String action,
      String entityType,
      String source,
      String actorUserId,
      String storeId,
      String detailsName,
      String detailsBarcode,
      String detailsInternalCode,
      String detailsPrice) {}

  /** Linha de {@code products} como o banco a guardou. */
  private record Product(
      String barcode,
      String internalCode,
      String unit,
      String price,
      boolean active,
      Object deletedAt) {}
}
