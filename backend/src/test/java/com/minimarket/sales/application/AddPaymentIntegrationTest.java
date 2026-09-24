package com.minimarket.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.f4b6a3.uuid.UuidCreator;
import com.minimarket.IntegrationTestBase;
import com.minimarket.cash.application.OpenCashSessionCommand;
import com.minimarket.cash.application.OpenCashSessionUseCase;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.sales.domain.Payment;
import com.minimarket.sales.domain.PaymentMethod;
import com.minimarket.sales.domain.PaymentTotals;
import com.minimarket.sales.domain.Sale;
import com.minimarket.shared.application.StoreLookup;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do pagamento contra PostgreSQL real (Dev Services) — critério de aceite do passo 904:
 * o pago/troco derivados dos pagamentos ficam nas colunas {@code sales.paid_amount}/{@code
 * change_amount} e voltam no agregado, inclusive depois de um update de item.
 *
 * <p>O pagamento é registrado pelas portas — linha em {@code payments}, totais derivados aplicados
 * no agregado e {@code update} numa transação só, como o caso de uso faz — em vez de pelo {@code
 * AddPaymentUseCase}: o backstop de permissão dele exige identidade autenticada, que só existe em
 * request HTTP (o teste de API é o passo 905). O que este teste prova é o efeito no banco e o
 * round-trip do agregado; o caso de uso tem os unitários próprios.
 *
 * <p>Sem {@code @TestTransaction}: a transação é a do cenário, como a API fará, e a limpeza do
 * {@code @AfterEach} segue a ordem que as FKs exigem: pagamentos → itens → venda → eventos → série
 * → movimentos → sessão de caixa → usuário → produto. A série da venda ({@code document_sequences})
 * é apagada antes e depois de cada teste, porque o banco é compartilhado com os demais testes do
 * fork; o barcode do produto tem sufixo aleatório pelo mesmo motivo.
 */
@QuarkusTest
class AddPaymentIntegrationTest extends IntegrationTestBase {

  private static final String OPERATOR = "vendas.pagamento.904";

  /** Série da venda em {@code document_sequences}; a NFC-e da Fase 14 terá a própria. */
  private static final String SALE_DOC_TYPE = "SALE";

  /**
   * Instante fixo do pagamento do cenário — não vem do banco, como o {@code Clock} do caso de uso.
   */
  private static final Instant PAID_AT = Instant.parse("2026-09-24T13:00:00Z");

  /** Barcode de 13 dígitos do produto do cenário, sem colidir com o de outra execução. */
  private static final String BARCODE =
      "7891000" + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000)) + "17";

  @Inject SaleStore saleStore;

  @Inject PaymentStore paymentStore;

  @Inject AddSaleItemUseCase addSaleItemUseCase;

  @Inject ChangeSaleItemQuantityUseCase changeSaleItemQuantityUseCase;

  @Inject CreateSaleUseCase createSaleUseCase;

  @Inject OpenCashSessionUseCase openCashSessionUseCase;

  @Inject ProductStore productStore;

  @Inject UserStore userStore;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  private UUID storeId;

  private UUID userId;

  private UUID productId;

  /** Caixa da sessão do cenário: é o caixa que a guarda de posse confere (passo 809a). */
  private UUID registerId;

  private UUID cashSessionId;

  private final List<UUID> saleIds = new ArrayList<>();

  @Test
  @DisplayName("pagamento grava paid_amount/change_amount na venda e o round-trip os restaura")
  void persistsPaidAndChangeAndRoundTrips() throws SQLException {
    Sale sale = openScenario("Arroz 5kg", "UN", "9.90");
    payCash(sale, "10.00", "20.00");

    assertThat(saleColumn("paid_amount", sale.id())).isEqualTo("10.00");
    assertThat(saleColumn("change_amount", sale.id())).isEqualTo("10.00");

    PaymentRow payment = paymentRow(sale.id());
    assertThat(payment.method()).isEqualTo("CASH");
    assertThat(payment.amount()).isEqualTo("10.00");
    assertThat(payment.tenderedAmount()).isEqualTo("20.00");
    assertThat(payment.changeAmount()).as("troco calculado pelo servidor").isEqualTo("10.00");
    assertThat(payment.status()).isEqualTo("APPROVED");
    assertThat(payment.createdByUserId()).isEqualTo(userId);

    Sale reloaded = callInOwnTransaction(() -> saleStore.findById(sale.id()).orElseThrow());
    assertThat(reloaded.total()).as("pagar não mexe no total").isEqualByComparingTo("19.80");
    assertThat(reloaded.paidAmount())
        .as("o round-trip restaura o pago")
        .isEqualByComparingTo("10.00");
    assertThat(reloaded.changeAmount()).isEqualByComparingTo("10.00");
  }

  @Test
  @DisplayName("editar item depois do pagamento preserva paid_amount/change_amount no banco")
  void keepsPaidAndChangeWhenItemChangesAfterPayment() throws SQLException {
    Sale sale = openScenario("Café 500g", "UN", "18.90");
    payCash(sale, "5.00", "10.00");
    assertThat(saleColumn("paid_amount", sale.id())).isEqualTo("5.00");
    assertThat(saleColumn("change_amount", sale.id())).isEqualTo("5.00");

    Sale updated =
        changeSaleItemQuantityUseCase.execute(
            new ChangeSaleItemQuantityCommand(
                sale.id(), registerId, productId, new BigDecimal("3")));

    assertThat(updated.subtotal()).as("o item mudou de 2 para 3").isEqualByComparingTo("56.70");
    assertThat(updated.paidAmount())
        .as("o pago volta do banco na leitura e continua na venda")
        .isEqualByComparingTo("5.00");
    assertThat(updated.changeAmount()).isEqualByComparingTo("5.00");
    assertThat(saleColumn("paid_amount", sale.id()))
        .as("o update do item não zera o pago")
        .isEqualTo("5.00");
    assertThat(saleColumn("change_amount", sale.id()))
        .as("o update do item não zera o troco")
        .isEqualTo("5.00");
  }

  /**
   * Registra o pagamento em dinheiro como o caso de uso faz, numa transação só: linha na porta,
   * totais derivados ({@link PaymentTotals}) aplicados no agregado relido e {@code update} — o
   * mesmo caminho que o {@code syncFrom} usa para gravar as colunas.
   */
  private void payCash(Sale sale, String amount, String tendered) {
    Payment payment =
        new Payment(
            UuidCreator.getTimeOrderedEpoch(),
            sale.id(),
            PaymentMethod.CASH,
            new BigDecimal(amount),
            new BigDecimal(tendered),
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

  /**
   * Operador, caixa aberto, venda e produto do cenário — venda e item pelos caminhos reais dos
   * passos 805/808. O item entra com quantidade 2: os testes contam com o total de {@code preço ×
   * 2}.
   */
  private Sale openScenario(String name, String unit, String price) throws SQLException {
    userId = newOperator();
    registerId = cashRegisterId("CAIXA-01");
    cashSessionId =
        openCashSessionUseCase
            .execute(new OpenCashSessionCommand(registerId, new BigDecimal("100.00"), userId, null))
            .id();
    Sale sale = createSaleUseCase.execute(new CreateSaleCommand(registerId, userId));
    saleIds.add(sale.id());
    productId =
        callInOwnTransaction(
            () ->
                productStore.insert(
                    new NewProduct(
                        storeId(), name, BARCODE, null, null, unit, new BigDecimal(price), null)));
    return addSaleItemUseCase.execute(
        new AddSaleItemCommand(sale.id(), registerId, BARCODE, null, new BigDecimal("2")));
  }

  /** Operador de verdade: a FK de {@code sales.operator_user_id} exige um usuário. */
  private UUID newOperator() {
    return callInOwnTransaction(
        () -> userStore.insert(new NewUser(OPERATOR, "Operador de pagamento", "hash", "ACTIVE")));
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

  /** Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}. */
  @AfterEach
  void removeCommittedRows() throws SQLException {
    for (UUID saleId : saleIds) {
      execute("delete from payments where sale_id = ?", saleId);
      execute("delete from sale_items where sale_id = ?", saleId);
      execute("delete from sales where id = ?", saleId);
      execute("delete from audit_events where entity_id = ?", saleId);
    }
    if (cashSessionId != null) {
      execute("delete from audit_events where entity_id = ?", cashSessionId);
      deleteSaleSequence();
      execute("delete from cash_movements where cash_session_id = ?", cashSessionId);
      execute("delete from cash_sessions where id = ?", cashSessionId);
    }
    if (userId != null) {
      execute("delete from users where id = ?", userId);
    }
    if (productId != null) {
      execute("delete from products where id = ?", productId);
    }
  }

  /**
   * Coluna crua da venda: é o que o {@code syncFrom} escreveu (e o que um update posterior
   * manteve).
   */
  private String saleColumn(String column, UUID saleId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select " + column + "::text from sales where id = ?")) {
      statement.setObject(1, saleId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("venda %s gravada", saleId).isTrue();
        return resultSet.getString(1);
      }
    }
  }

  /** Linha de {@code payments} como o banco a guardou. */
  private PaymentRow paymentRow(UUID saleId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select id, method, amount::text as amount, tendered_amount::text as tendered_amount,"
                    + " change_amount::text as change_amount, status, created_by_user_id"
                    + " from payments where sale_id = ?")) {
      statement.setObject(1, saleId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("pagamento da venda %s gravado", saleId).isTrue();
        return new PaymentRow(
            resultSet.getObject("id", UUID.class),
            resultSet.getString("method"),
            resultSet.getString("amount"),
            resultSet.getString("tendered_amount"),
            resultSet.getString("change_amount"),
            resultSet.getString("status"),
            resultSet.getObject("created_by_user_id", UUID.class));
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

  /** Linha de {@code payments} como o banco a guardou. */
  private record PaymentRow(
      UUID id,
      String method,
      String amount,
      String tenderedAmount,
      String changeAmount,
      String status,
      UUID createdByUserId) {}
}
