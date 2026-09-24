package com.minimarket.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.f4b6a3.uuid.UuidCreator;
import com.minimarket.IntegrationTestBase;
import com.minimarket.cash.application.OpenCashSessionCommand;
import com.minimarket.cash.application.OpenCashSessionUseCase;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.inventory.application.ApplyStockMovementCommand;
import com.minimarket.inventory.application.StockService;
import com.minimarket.inventory.domain.StockMovementType;
import com.minimarket.sales.domain.Payment;
import com.minimarket.sales.domain.PaymentMethod;
import com.minimarket.sales.domain.PaymentTotals;
import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleStatus;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.users.application.NewUser;
import com.minimarket.users.application.UserStore;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração da conclusão da venda contra PostgreSQL real (Dev Services) — critério de aceite do
 * passo 906: a venda aberta e paga conclui movendo estoque, caixa e auditoria na mesma transação;
 * pagar de menos recusa sem tocar em nada; concluir de novo é no-op; e qualquer falha no meio não
 * deixa rastro (rollback total).
 *
 * <p>O caso de uso é chamado direto, sem request HTTP e sem identidade autenticada — ele não tem
 * backstop de permissão, como o {@code CreateSaleUseCase} (a permissão {@code sale.complete} é da
 * API, passo 907). Os pagamentos entram pelas portas ({@code PaymentStore.insert} + {@code
 * Sale.applyPaymentTotals} + {@code SaleStore.update}) porque o {@code AddPaymentUseCase} tem
 * backstop de permissão e só roda com identidade autenticada — o mesmo caminho do {@code
 * AddPaymentIntegrationTest}. As demais fixtures saem dos caminhos reais: operador pela {@code
 * UserStore}, produto pela {@code ProductStore}, estoque pelo {@code StockService} (INITIAL),
 * sessão pelo {@code OpenCashSessionUseCase}, venda pelo {@code CreateSaleUseCase} e itens pelo
 * {@code AddSaleItemUseCase}.
 *
 * <p>Sem {@code @TestTransaction}: a transação é a do cenário e o teste confere por SQL o que ficou
 * comitado — a baixa de estoque com o {@code balance_after}, o movimento {@code SALE} do caixa com
 * a parcela em dinheiro, a linha da venda e o evento {@code SALE_COMPLETED}. A limpeza do
 * {@code @AfterEach} segue a ordem que as FKs exigem: pagamentos → itens → venda → eventos →
 * movimentos de estoque → saldo → movimentos do caixa → sessão → série → usuário → produtos. A
 * série da venda ({@code document_sequences}) é apagada antes e depois de cada teste e o teste que
 * desliga {@code allow_negative_stock} restaura a flag no {@code @AfterEach}, como o {@code
 * StockServiceIntegrationTest} — a loja e o banco são compartilhados com os demais testes do fork.
 */
@QuarkusTest
class CompleteSaleIntegrationTest extends IntegrationTestBase {

  private static final String OPERATOR = "vendas.conclusao.906";

  /** Série da venda em {@code document_sequences}; a NFC-e da Fase 14 terá a própria. */
  private static final String SALE_DOC_TYPE = "SALE";

  /** Instante do pagamento do cenário — não vem do banco, como o {@code Clock} do caso de uso. */
  private static final Instant PAID_AT = Instant.parse("2026-09-24T13:00:00Z");

  @Inject CompleteSaleUseCase completeSaleUseCase;

  @Inject CreateSaleUseCase createSaleUseCase;

  @Inject AddSaleItemUseCase addSaleItemUseCase;

  @Inject OpenCashSessionUseCase openCashSessionUseCase;

  @Inject StockService stockService;

  @Inject SaleStore saleStore;

  @Inject PaymentStore paymentStore;

  @Inject ProductStore productStore;

  @Inject UserStore userStore;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  private UUID storeId;

  private UUID userId;

  /** Produto principal do cenário: é ele que tem estoque inicial e recebe o SALE_OUT. */
  private UUID productId;

  /** Caixa da sessão do cenário: é o caixa que a guarda de posse confere (BR-11). */
  private UUID registerId;

  private UUID cashSessionId;

  private final List<UUID> saleIds = new ArrayList<>();

  private final List<UUID> productIds = new ArrayList<>();

  private boolean storeFlagFlipped;

  @Test
  @DisplayName(
      "conclui a venda: baixa estoque com balance_after, entra a parcela em dinheiro no caixa e"
          + " audita os totais e as formas")
  void completesSaleMovingStockCashAndAudit() throws SQLException {
    Sale sale = openScenario("Arroz 5kg", "9.90", "10.000");
    pay(sale, PaymentMethod.CASH, "10.00", "10.00");
    pay(sale, PaymentMethod.CREDIT, "9.80", null);

    Sale completed =
        completeSaleUseCase.execute(new CompleteSaleCommand(sale.id(), registerId, userId));

    assertThat(completed.status()).isEqualTo(SaleStatus.COMPLETED);
    assertThat(completed.completedAt()).isNotNull();
    assertThat(completed.total()).as("concluir não mexe no total").isEqualByComparingTo("19.80");
    assertThat(completed.paidAmount()).isEqualByComparingTo("19.80");

    SaleRow stored = saleRow(sale.id());
    assertThat(stored.status()).isEqualTo("COMPLETED");
    assertThat(stored.completedAt()).isNotNull();
    assertThat(stored.paidAmount()).isEqualTo("19.80");
    assertThat(stored.changeAmount()).isEqualTo("0.00");

    List<MovementRow> ledger = stockMovements(productId);
    assertThat(ledger).extracting(MovementRow::type).containsExactly("INITIAL", "SALE_OUT");
    MovementRow out = ledger.get(1);
    assertThat(out.delta()).isEqualByComparingTo("-2.000");
    assertThat(out.balanceAfter()).as("10 − 2 do item vendido").isEqualByComparingTo("8.000");
    assertThat(out.unitCost()).isNull();
    assertThat(out.referenceType()).isEqualTo("SALE");
    assertThat(out.referenceId()).isEqualTo(sale.id());
    assertThat(out.reason()).isNull();
    assertThat(out.createdByUserId()).isEqualTo(userId);
    assertThat(quantity(productId))
        .as("o saldo materializado segue o ledger")
        .isEqualByComparingTo("8.000");

    List<CashMovementRow> movements = cashMovements();
    assertThat(movements)
        .extracting(CashMovementRow::type)
        .as("a venda mista entra no caixa só pela parcela em dinheiro")
        .containsExactly("OPENING", "SALE");
    CashMovementRow saleMovement = movements.get(1);
    assertThat(saleMovement.amount())
        .as("nunca o valor entregue (20,00)")
        .isEqualByComparingTo("10.00");
    assertThat(saleMovement.paymentMethod()).isEqualTo("CASH");
    assertThat(saleMovement.referenceType()).isEqualTo("SALE");
    assertThat(saleMovement.referenceId()).isEqualTo(sale.id());
    assertThat(saleMovement.reason()).isNull();
    assertThat(saleMovement.createdByUserId()).isEqualTo(userId);
    assertThat(saleMovement.createdAt())
        .as("o movimento carrega o instante da conclusão")
        .isEqualTo(stored.completedAt());

    AuditEvent event = eventOf(sale.id(), "SALE_COMPLETED");
    assertThat(event.entityType()).isEqualTo("SALE");
    assertThat(event.source()).as("sem request HTTP o evento é de sistema").isEqualTo("SYSTEM");
    assertThat(event.reason()).isNull();
    JsonPath details = JsonPath.from(event.details());
    assertThat(details.getLong("number")).isEqualTo(sale.number());
    assertThat(number(details.get("total"))).isEqualByComparingTo("19.80");
    assertThat(number(details.get("paidAmount"))).isEqualByComparingTo("19.80");
    assertThat(number(details.get("changeAmount"))).isEqualByComparingTo("0.00");
    Map<String, Object> byMethod = details.getMap("paymentsByMethod");
    assertThat(byMethod.keySet()).as("formas na ordem do enum").containsExactly("CASH", "CREDIT");
    assertThat(number(byMethod.get("CASH"))).isEqualByComparingTo("10.00");
    assertThat(number(byMethod.get("CREDIT"))).isEqualByComparingTo("9.80");
  }

  @Test
  @DisplayName("venda só em cartão baixa o estoque e não gera movimento de caixa")
  void completesCardOnlySaleWithoutCashMovement() throws SQLException {
    Sale sale = openScenario("Café 500g", "18.90", "5.000");
    pay(sale, PaymentMethod.CREDIT, "37.80", null);

    Sale completed =
        completeSaleUseCase.execute(new CompleteSaleCommand(sale.id(), registerId, userId));

    assertThat(completed.status()).isEqualTo(SaleStatus.COMPLETED);
    assertThat(quantity(productId)).isEqualByComparingTo("3.000");
    assertThat(stockMovements(productId))
        .extracting(MovementRow::type)
        .containsExactly("INITIAL", "SALE_OUT");
    assertThat(cashMovements())
        .extracting(CashMovementRow::type)
        .as("nenhum dinheiro passou pelo caixa")
        .containsExactly("OPENING");
    assertThat(eventOf(sale.id(), "SALE_COMPLETED").entityType()).isEqualTo("SALE");
  }

  @Test
  @DisplayName("pagamento insuficiente: 422 PAYMENT_INSUFFICIENT e nada muda")
  void refusesUnpaidSaleWithoutSideEffects() throws SQLException {
    Sale sale = openScenario("Feijão 1kg", "8.50", "4.000");
    pay(sale, PaymentMethod.PIX, "10.00", null);

    assertThatThrownBy(
            () ->
                completeSaleUseCase.execute(new CompleteSaleCommand(sale.id(), registerId, userId)))
        .as("BR-05: o pagamento precisa cobrir o total")
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.PAYMENT_INSUFFICIENT);
              assertThat(error).hasMessageContaining(sale.id().toString());
            });

    SaleRow stored = saleRow(sale.id());
    assertThat(stored.status()).isEqualTo("OPEN");
    assertThat(stored.completedAt()).isNull();
    assertThat(quantity(productId)).as("o estoque não foi tocado").isEqualByComparingTo("4.000");
    assertThat(stockMovements(productId)).extracting(MovementRow::type).containsExactly("INITIAL");
    assertThat(cashMovements()).extracting(CashMovementRow::type).containsExactly("OPENING");
    assertThat(auditEventCount(sale.id(), "SALE_COMPLETED")).isZero();
  }

  @Test
  @DisplayName(
      "concluir de novo é no-op: sem segunda baixa, sem segundo movimento e sem segundo evento")
  void replayIsNoOpByState() throws SQLException {
    Sale sale = openScenario("Leite 1L", "5.50", "6.000");
    pay(sale, PaymentMethod.CASH, "11.00", "20.00");

    completeSaleUseCase.execute(new CompleteSaleCommand(sale.id(), registerId, userId));
    SaleRow afterFirst = saleRow(sale.id());
    Sale replayed =
        completeSaleUseCase.execute(new CompleteSaleCommand(sale.id(), registerId, userId));

    assertThat(replayed.status()).isEqualTo(SaleStatus.COMPLETED);
    assertThat(replayed.completedAt())
        .as("o estado é o da primeira conclusão, gravado no banco")
        .isEqualTo(afterFirst.completedAt());
    assertThat(replayed.changeAmount()).isEqualByComparingTo("9.00");
    assertThat(saleRow(sale.id()).version())
        .as("o no-op não regrava a venda")
        .isEqualTo(afterFirst.version());
    assertThat(quantity(productId)).as("uma baixa só").isEqualByComparingTo("4.000");
    assertThat(stockMovements(productId))
        .extracting(MovementRow::type)
        .containsExactly("INITIAL", "SALE_OUT");
    assertThat(cashMovements())
        .extracting(CashMovementRow::type)
        .as("um movimento SALE, não dois")
        .containsExactly("OPENING", "SALE");
    assertThat(auditEventCount(sale.id(), "SALE_COMPLETED")).as("um evento, não dois").isEqualTo(1);
  }

  @Test
  @DisplayName(
      "rollback total com allow_negative_stock=false: 422 INSUFFICIENT_STOCK e nenhum rastro")
  void rollsBackEverythingWhenAnItemHasNoBalance() throws SQLException {
    setAllowNegativeStock(false);
    Sale sale = openScenario("Açúcar 1kg", "4.25", "5.000");
    UUID emptyProduct = newProduct("Farinha 1kg", "6.75");
    addSaleItemUseCase.execute(
        new AddSaleItemCommand(sale.id(), registerId, null, emptyProduct, new BigDecimal("2")));
    pay(sale, PaymentMethod.CASH, "22.00", "22.00");

    assertThatThrownBy(
            () ->
                completeSaleUseCase.execute(new CompleteSaleCommand(sale.id(), registerId, userId)))
        .as("BR-09: a loja não permite saldo negativo")
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
              assertThat(error).hasMessageContaining(emptyProduct.toString());
            });

    SaleRow stored = saleRow(sale.id());
    assertThat(stored.status()).as("a venda não concluiu").isEqualTo("OPEN");
    assertThat(stored.completedAt()).isNull();
    assertThat(quantity(productId))
        .as("o item que tinha saldo também não foi baixado")
        .isEqualByComparingTo("5.000");
    assertThat(quantity(emptyProduct))
        .as("o saldo criado sob demanda voltou atrás no rollback")
        .isNull();
    assertThat(stockMovements(productId))
        .as("do lote inteiro não sobrou linha de SALE_OUT")
        .extracting(MovementRow::type)
        .containsExactly("INITIAL");
    assertThat(stockMovements(emptyProduct)).isEmpty();
    assertThat(cashMovements()).extracting(CashMovementRow::type).containsExactly("OPENING");
    assertThat(auditEventCount(sale.id(), "SALE_COMPLETED")).isZero();
  }

  /**
   * Operador, caixa aberto, produto com estoque, venda e item do cenário — venda e item pelos
   * caminhos reais dos passos 805/808. O item entra com quantidade 2: os testes contam com o total
   * de {@code preço × 2}.
   */
  private Sale openScenario(String name, String price, String initialStock) throws SQLException {
    userId = newOperator();
    registerId = cashRegisterId("CAIXA-01");
    cashSessionId =
        openCashSessionUseCase
            .execute(new OpenCashSessionCommand(registerId, new BigDecimal("100.00"), userId, null))
            .id();
    Sale sale = createSaleUseCase.execute(new CreateSaleCommand(registerId, userId));
    saleIds.add(sale.id());
    productId = newProduct(name, price);
    stockService.applyMovement(
        new ApplyStockMovementCommand(
            productId,
            StockMovementType.INITIAL,
            new BigDecimal(initialStock),
            null,
            null,
            null,
            null,
            userId));
    return addSaleItemUseCase.execute(
        new AddSaleItemCommand(sale.id(), registerId, null, productId, new BigDecimal("2")));
  }

  /**
   * Registra o pagamento como o caso de uso faz, numa transação só: linha na porta, totais
   * derivados somados pelo {@link PaymentTotals} e {@code update} da venda — é o que decide a
   * conclusão (BR-05). O valor entregue só existe em dinheiro.
   */
  private void pay(Sale sale, PaymentMethod method, String amount, String tendered) {
    Payment payment =
        new Payment(
            UuidCreator.getTimeOrderedEpoch(),
            sale.id(),
            method,
            new BigDecimal(amount),
            tendered == null ? null : new BigDecimal(tendered),
            userId,
            PAID_AT);
    callInOwnTransaction(
        () -> {
          paymentStore.insert(payment);
          Sale loaded = saleStore.findById(sale.id()).orElseThrow();
          loaded.applyPaymentTotals(PaymentTotals.of(paymentStore.listBySale(sale.id())));
          saleStore.update(loaded);
          return null;
        });
  }

  /** Operador de verdade: as FKs de venda, pagamento e ledger de estoque exigem um usuário. */
  private UUID newOperator() {
    return callInOwnTransaction(
        () -> userStore.insert(new NewUser(OPERATOR, "Operador da conclusão", "hash", "ACTIVE")));
  }

  /** Produto vivo da loja, pela porta do catálogo — nasce sem código de barras. */
  private UUID newProduct(String name, String price) {
    UUID id =
        callInOwnTransaction(
            () ->
                productStore.insert(
                    new NewProduct(
                        storeId(), name, null, null, null, "UN", new BigDecimal(price), null)));
    productIds.add(id);
    return id;
  }

  /**
   * Desliga a flag da loja direto no banco (não há porta de escrita da loja no MVP) antes de
   * qualquer leitura da loja no teste, e marca a flag para o {@code @AfterEach} restaurar — a loja
   * é compartilhada com os outros testes do fork.
   */
  private void setAllowNegativeStock(boolean allowed) throws SQLException {
    storeFlagFlipped = true;
    execute("update stores set allow_negative_stock = ? where code = ?", allowed, defaultStoreCode);
  }

  /** A série nasce na primeira alocação: nenhum teste pode herdar o contador do anterior. */
  @BeforeEach
  void resetSaleSequence() throws SQLException {
    deleteSaleSequence();
  }

  private void deleteSaleSequence() throws SQLException {
    execute(
        "delete from document_sequences where store_id = ? and doc_type = ?",
        storeId(),
        SALE_DOC_TYPE);
  }

  /** Remove o que o teste comitou e restaura a flag da loja — o banco é compartilhado. */
  @AfterEach
  void removeCommittedRows() throws SQLException {
    for (UUID saleId : saleIds) {
      execute("delete from payments where sale_id = ?", saleId);
      execute("delete from sale_items where sale_id = ?", saleId);
      execute("delete from sales where id = ?", saleId);
      execute("delete from audit_events where entity_id = ?", saleId);
    }
    for (UUID id : productIds) {
      execute("delete from stock_movements where product_id = ?", id);
      execute("delete from product_stocks where product_id = ?", id);
      execute("delete from products where id = ?", id);
    }
    if (cashSessionId != null) {
      execute("delete from audit_events where entity_id = ?", cashSessionId);
      execute("delete from cash_movements where cash_session_id = ?", cashSessionId);
      execute("delete from cash_sessions where id = ?", cashSessionId);
    }
    deleteSaleSequence();
    if (userId != null) {
      execute("delete from users where id = ?", userId);
    }
    if (storeFlagFlipped) {
      setAllowNegativeStock(true);
    }
  }

  /** Linha da venda como o banco a guardou. */
  private SaleRow saleRow(UUID saleId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select status, total::text as total, paid_amount::text as paid_amount,"
                    + " change_amount::text as change_amount, completed_at, version"
                    + " from sales where id = ?")) {
      statement.setObject(1, saleId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("venda %s gravada", saleId).isTrue();
        return new SaleRow(
            resultSet.getString("status"),
            resultSet.getString("total"),
            resultSet.getString("paid_amount"),
            resultSet.getString("change_amount"),
            instant(resultSet.getTimestamp("completed_at")),
            resultSet.getLong("version"));
      }
    }
  }

  /** Linhas do ledger do produto em ordem de aplicação ({@code created_at}, id do UUIDv7). */
  private List<MovementRow> stockMovements(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select type, quantity_delta::text as delta, balance_after::text as balance_after,"
                    + " unit_cost::text as unit_cost, reference_type, reference_id, reason,"
                    + " created_by_user_id from stock_movements where product_id = ?"
                    + " order by created_at, id")) {
      statement.setObject(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<MovementRow> rows = new ArrayList<>();
        while (resultSet.next()) {
          rows.add(
              new MovementRow(
                  resultSet.getString("type"),
                  new BigDecimal(resultSet.getString("delta")),
                  new BigDecimal(resultSet.getString("balance_after")),
                  resultSet.getString("unit_cost") == null
                      ? null
                      : new BigDecimal(resultSet.getString("unit_cost")),
                  resultSet.getString("reference_type"),
                  resultSet.getObject("reference_id", UUID.class),
                  resultSet.getString("reason"),
                  resultSet.getObject("created_by_user_id", UUID.class)));
        }
        return rows;
      }
    }
  }

  /** Saldo materializado do produto; nulo quando o produto não tem linha de saldo. */
  private BigDecimal quantity(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select quantity::text from product_stocks where store_id = ? and product_id = ?")) {
      statement.setObject(1, storeId());
      statement.setObject(2, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? new BigDecimal(resultSet.getString(1)) : null;
      }
    }
  }

  /** Movimentos do ledger do caixa da sessão em ordem cronológica. */
  private List<CashMovementRow> cashMovements() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select type, amount::text as amount, payment_method, reference_type, reference_id,"
                    + " reason, created_by_user_id, created_at from cash_movements"
                    + " where cash_session_id = ? order by created_at, id")) {
      statement.setObject(1, cashSessionId);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<CashMovementRow> rows = new ArrayList<>();
        while (resultSet.next()) {
          rows.add(
              new CashMovementRow(
                  resultSet.getString("type"),
                  new BigDecimal(resultSet.getString("amount")),
                  resultSet.getString("payment_method"),
                  resultSet.getString("reference_type"),
                  resultSet.getObject("reference_id", UUID.class),
                  resultSet.getString("reason"),
                  resultSet.getObject("created_by_user_id", UUID.class),
                  instant(resultSet.getTimestamp("created_at"))));
        }
        return rows;
      }
    }
  }

  /**
   * Evento da ação pelo alvo da venda, com {@code details} no texto do jsonb; falha se houver zero
   * ou mais de um evento para o par — a conferência é sempre por {@code entity_id} + {@code
   * action}.
   */
  private AuditEvent eventOf(UUID saleId, String action) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select entity_type, source, reason, details::text as details from audit_events"
                    + " where action = ? and entity_id = ?")) {
      statement.setString(1, action);
      statement.setObject(2, saleId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("evento %s da venda %s", action, saleId).isTrue();
        AuditEvent event =
            new AuditEvent(
                resultSet.getString("entity_type"),
                resultSet.getString("source"),
                resultSet.getString("reason"),
                resultSet.getString("details"));
        assertThat(resultSet.next()).as("uma operação, um evento %s", action).isFalse();
        return event;
      }
    }
  }

  /** Quantos eventos da ação a venda tem — a prova de que o no-op não inventa rastro. */
  private int auditEventCount(UUID saleId, String action) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from audit_events where action = ? and entity_id = ?")) {
      statement.setString(1, action);
      statement.setObject(2, saleId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
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

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  /**
   * Valor numérico do JSON como BigDecimal: o JsonPath devolve decimal como {@code Float}, então o
   * dinheiro do evento é conferido por valor ({@code compareTo}), não pelo texto.
   */
  private static BigDecimal number(Object value) {
    assertThat(value).as("número no details do evento").isNotNull();
    return new BigDecimal(value.toString());
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      statement.executeUpdate();
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

  /** Linha da venda com os campos que a suíte confere. */
  private record SaleRow(
      String status,
      String total,
      String paidAmount,
      String changeAmount,
      Instant completedAt,
      long version) {}

  /** Linha de {@code stock_movements} como o banco a guardou. */
  private record MovementRow(
      String type,
      BigDecimal delta,
      BigDecimal balanceAfter,
      BigDecimal unitCost,
      String referenceType,
      UUID referenceId,
      String reason,
      UUID createdByUserId) {}

  /** Linha de {@code cash_movements} como o banco a guardou. */
  private record CashMovementRow(
      String type,
      BigDecimal amount,
      String paymentMethod,
      String referenceType,
      UUID referenceId,
      String reason,
      UUID createdByUserId,
      Instant createdAt) {}

  /** Linha de {@code audit_events} com os campos que a suíte confere. */
  private record AuditEvent(String entityType, String source, String reason, String details) {}
}
