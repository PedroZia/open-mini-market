package com.minimarket.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.minimarket.IntegrationTestBase;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.inventory.domain.StockMovementType;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.users.application.NewUser;
import com.minimarket.users.application.UserStore;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link StockService} contra PostgreSQL real (Dev Services): o serviço é chamado de
 * verdade, com transação própria por chamada (sem {@code @TestTransaction}), e o teste confere por
 * SQL o que ficou no banco — saldo materializado, uma linha de ledger por movimento com o {@code
 * balance_after} da sequência, o instante único do lote e a recusa do saldo negativo.
 *
 * <p>A loja do cenário é a configurada ({@code MATRIZ}, do seed da V1), compartilhada com os demais
 * testes do fork: o teste que desliga {@code allow_negative_stock} restaura a flag no
 * {@code @AfterEach}. A fixture (operador e produto) sai das portas, em transação própria, e a
 * limpeza segue a ordem inversa das FKs: ledger → saldo → produto → usuário.
 */
@QuarkusTest
class StockServiceIntegrationTest extends IntegrationTestBase {

  private static final String OPERATOR = "estoque.servico.703";

  @Inject StockService stockService;

  @Inject ProductStore productStore;

  @Inject UserStore userStore;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  private UUID storeId;

  private UUID userId;

  private UUID productId;

  private boolean storeFlagFlipped;

  @Test
  @DisplayName(
      "sequência 0 → 10 → 7 → -2: balance_after correto, um movimento por delta e a soma batendo")
  void appliesSequenceAndKeepsLedgerConsistent() throws SQLException {
    userId = newOperator();
    productId = newProduct();

    AppliedStockMovement initial =
        stockService.applyMovement(command(productId, StockMovementType.INITIAL, "10.000"));
    List<AppliedStockMovement> batch =
        stockService.applyMovements(
            List.of(
                command(productId, StockMovementType.SALE_OUT, "-3.000"),
                command(productId, StockMovementType.LOSS, "-9.000")));

    assertThat(initial.balanceBefore()).isEqualByComparingTo("0");
    assertThat(initial.balanceAfter()).isEqualByComparingTo("10.000");
    assertThat(batch).hasSize(2);
    assertThat(batch.get(0).balanceBefore()).isEqualByComparingTo("10.000");
    assertThat(batch.get(0).balanceAfter()).isEqualByComparingTo("7.000");
    assertThat(batch.get(1).balanceBefore()).isEqualByComparingTo("7.000");
    assertThat(batch.get(1).balanceAfter()).isEqualByComparingTo("-2.000");

    List<MovementRow> ledger = ledgerRows();
    assertThat(ledger)
        .extracting(MovementRow::id)
        .as("uma linha de ledger por movimento, na ordem em que foram aplicados")
        .containsExactly(
            initial.movementId(), batch.get(0).movementId(), batch.get(1).movementId());
    assertThat(ledger).extracting(MovementRow::type).containsExactly("INITIAL", "SALE_OUT", "LOSS");
    assertThat(ledger.get(0).delta()).isEqualByComparingTo("10.000");
    assertThat(ledger.get(0).balanceAfter()).isEqualByComparingTo("10.000");
    assertThat(ledger.get(1).delta()).isEqualByComparingTo("-3.000");
    assertThat(ledger.get(1).balanceAfter()).isEqualByComparingTo("7.000");
    assertThat(ledger.get(2).delta()).isEqualByComparingTo("-9.000");
    assertThat(ledger.get(2).balanceAfter()).isEqualByComparingTo("-2.000");
    assertThat(ledger)
        .extracting(MovementRow::storeId)
        .as("o movimento é da loja configurada")
        .containsOnly(storeId());
    assertThat(ledger).extracting(MovementRow::createdByUserId).containsOnly(userId);
    assertThat(ledger.get(1).createdAt())
        .as("os movimentos do mesmo lote compartilham o clock.instant() da chamada")
        .isEqualTo(ledger.get(2).createdAt());
    assertThat(ledger.get(0).createdAt())
        .as("o instante vem do relógio da aplicação, não do now() do banco")
        .isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));

    BigDecimal ledgerSum =
        ledger.stream().map(MovementRow::delta).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(ledgerSum).as("a soma dos deltas é o saldo final").isEqualByComparingTo("-2.000");
    assertThat(quantity())
        .as("o saldo materializado bate com o ledger")
        .isEqualByComparingTo("-2.000");
  }

  @Test
  @DisplayName(
      "saldo insuficiente com allow_negative_stock=false: 422 INSUFFICIENT_STOCK e nada gravado")
  void blocksNegativeBalanceWhenStoreForbidsIt() throws SQLException {
    setAllowNegativeStock(false);
    userId = newOperator();
    productId = newProduct();

    AppliedStockMovement received =
        stockService.applyMovement(command(productId, StockMovementType.PURCHASE_IN, "5.000"));
    assertThat(received.balanceAfter()).isEqualByComparingTo("5.000");

    assertThatThrownBy(
            () ->
                stockService.applyMovement(
                    command(productId, StockMovementType.SALE_OUT, "-6.000")))
        .as("BR-09: a loja não permite saldo negativo")
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
              assertThat(error).hasMessageContaining(productId.toString());
            });

    assertThat(quantity())
        .as("o saldo do movimento recusado não mudou")
        .isEqualByComparingTo("5.000");
    assertThat(ledgerRows())
        .as("o movimento recusado não deixou linha no ledger")
        .extracting(MovementRow::delta)
        .containsExactly(new BigDecimal("5.000"));
  }

  /** Operador de verdade: o {@code created_by_user_id} do ledger tem FK para {@code users}. */
  private UUID newOperator() {
    return callInOwnTransaction(
        () -> userStore.insert(new NewUser(OPERATOR, "Operador de estoque", "hash", "ACTIVE")));
  }

  /** Produto vivo da loja, pela porta do catálogo. */
  private UUID newProduct() {
    return callInOwnTransaction(
        () ->
            productStore.insert(
                new NewProduct(
                    storeId(),
                    "Produto do StockService",
                    null,
                    null,
                    null,
                    "UN",
                    new BigDecimal("9.90"),
                    null)));
  }

  /** Movimento do cenário: delta assinado e operador do teste; sem custo, referência ou motivo. */
  private ApplyStockMovementCommand command(
      UUID productId, StockMovementType type, String quantityDelta) {
    return new ApplyStockMovementCommand(
        productId, type, new BigDecimal(quantityDelta), null, null, null, null, userId);
  }

  /**
   * Desliga a flag da loja direto no banco (não há porta de escrita da loja no MVP) antes de
   * qualquer leitura da loja no teste, e marca a flag para o {@code @AfterEach} restaurar — a loja
   * é compartilhada com os outros testes do fork.
   */
  private void setAllowNegativeStock(boolean allowed) throws SQLException {
    storeFlagFlipped = true;
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "update stores set allow_negative_stock = ? where code = ?")) {
      statement.setBoolean(1, allowed);
      statement.setString(2, defaultStoreCode);
      statement.executeUpdate();
    }
  }

  /** Remove o que o teste comitou e restaura a flag da loja — o banco é compartilhado. */
  @AfterEach
  void removeCommittedRows() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      execute(connection, "delete from stock_movements where product_id = ?", productId);
      execute(connection, "delete from product_stocks where product_id = ?", productId);
      execute(connection, "delete from products where id = ?", productId);
      execute(connection, "delete from users where id = ?", userId);
    }
    if (storeFlagFlipped) {
      setAllowNegativeStock(true);
    }
  }

  private static void execute(Connection connection, String sql, UUID id) throws SQLException {
    if (id == null) {
      return;
    }
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      statement.executeUpdate();
    }
  }

  /** Linhas do ledger do produto em ordem de aplicação ({@code created_at}, id do UUIDv7). */
  private List<MovementRow> ledgerRows() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select id, store_id, type, quantity_delta::text as delta,"
                    + " balance_after::text as balance_after, created_by_user_id, created_at"
                    + " from stock_movements where product_id = ? order by created_at, id")) {
      statement.setObject(1, productId);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<MovementRow> rows = new ArrayList<>();
        while (resultSet.next()) {
          rows.add(
              new MovementRow(
                  resultSet.getObject("id", UUID.class),
                  resultSet.getObject("store_id", UUID.class),
                  resultSet.getString("type"),
                  new BigDecimal(resultSet.getString("delta")),
                  new BigDecimal(resultSet.getString("balance_after")),
                  resultSet.getObject("created_by_user_id", UUID.class),
                  resultSet.getTimestamp("created_at").toInstant()));
        }
        return rows;
      }
    }
  }

  /** Saldo materializado do par (loja, produto), como o banco o guardou. */
  private BigDecimal quantity() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select quantity::text from product_stocks where store_id = ? and product_id = ?")) {
      statement.setObject(1, storeId());
      statement.setObject(2, productId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("linha de saldo do produto presente").isTrue();
        return new BigDecimal(resultSet.getString(1));
      }
    }
  }

  /** Roda a tarefa em transação própria, com o request context do Arc ativado na thread. */
  private static <T> T callInOwnTransaction(Callable<T> work) {
    ManagedContext requestContext = Arc.container().requestContext();
    boolean activated = !requestContext.isActive();
    if (activated) {
      requestContext.activate();
    }
    try {
      return QuarkusTransaction.requiringNew().call(work);
    } finally {
      if (activated) {
        requestContext.terminate();
      }
    }
  }

  /** Id da loja configurada: a porta {@code StoreLookup} devolve o id desde o passo 204a. */
  private UUID storeId() {
    if (storeId == null) {
      storeId =
          callInOwnTransaction(
              () ->
                  storeLookup
                      .findByCode(defaultStoreCode)
                      .orElseThrow(() -> new IllegalStateException("loja do seed da V1 ausente"))
                      .id());
    }
    return storeId;
  }

  /** Linha de {@code stock_movements} como o banco a guardou. */
  private record MovementRow(
      UUID id,
      UUID storeId,
      String type,
      BigDecimal delta,
      BigDecimal balanceAfter,
      UUID createdByUserId,
      Instant createdAt) {}
}
