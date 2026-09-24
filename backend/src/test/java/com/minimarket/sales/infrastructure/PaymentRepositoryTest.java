package com.minimarket.sales.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.f4b6a3.uuid.UuidCreator;
import com.minimarket.IntegrationTestBase;
import com.minimarket.sales.domain.Payment;
import com.minimarket.sales.domain.PaymentMethod;
import com.minimarket.sales.domain.PaymentStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link PaymentRepository} contra PostgreSQL real (Dev Services): round-trip de
 * todos os campos (dinheiro com troco e as demais formas sem valor entregue), isolamento e ordem
 * por venda, soma dos aprovados e cancelamento. Critério de aceite do passo 903 — round-trip e soma
 * correta.
 *
 * <p>As fixtures (loja MATRIZ e CAIXA-01 dos seeds, usuário, sessão de caixa e vendas) são criadas
 * por SQL e comitadas no {@code @BeforeEach}, como no {@code SaleRepositoryTest}; os testes rodam
 * em transação que comita no fim ({@code @Transactional}) e o {@code @AfterEach} limpa o que ficou,
 * na ordem das FKs: {@code payments} → {@code sales} → {@code cash_sessions} → {@code users}. As
 * vendas nascem sem item de propósito: o pagamento não depende de item e o teste não precisa de
 * produto.
 */
@QuarkusTest
class PaymentRepositoryTest extends IntegrationTestBase {

  private static final Instant REGISTERED_AT = Instant.parse("2026-09-24T10:00:00Z");

  @Inject PaymentRepository paymentRepository;

  @Inject EntityManager entityManager;

  private UUID storeId;
  private UUID registerId;
  private UUID operatorId;
  private UUID sessionId;
  private UUID saleId;
  private UUID otherSaleId;

  @BeforeEach
  void prepareFixtures() throws SQLException {
    storeId = idByCode("stores", "MATRIZ");
    registerId = cashRegisterId("CAIXA-01");
    operatorId = createUser("pagamentos.repo");
    sessionId = openSession();
    saleId = createSale(9001L);
    otherSaleId = createSale(9002L);
  }

  @AfterEach
  void removeFixtures() throws SQLException {
    execute("delete from payments where sale_id = ?", saleId);
    execute("delete from payments where sale_id = ?", otherSaleId);
    execute("delete from sales where id = ?", saleId);
    execute("delete from sales where id = ?", otherSaleId);
    deleteIfPresent("cash_sessions", sessionId);
    deleteIfPresent("users", operatorId);
  }

  @Test
  @Transactional
  @DisplayName(
      "insert grava o pagamento (UUIDv7) e listBySale devolve o round-trip de todos os campos")
  void insertsAndFindsBySale() {
    Payment cash = payment(saleId, PaymentMethod.CASH, "50.00", "60.00", REGISTERED_AT);
    Payment pix = payment(saleId, PaymentMethod.PIX, "12.35", null, REGISTERED_AT.plusSeconds(1));
    paymentRepository.insert(cash);
    paymentRepository.insert(pix);
    flushAndClear();

    assertThat(cash.id().version()).as("id do pagamento é UUIDv7").isEqualTo(7);
    assertThat(paymentRepository.listBySale(saleId))
        .satisfiesExactly(
            found -> {
              assertThat(found.id()).isEqualTo(cash.id());
              assertThat(found.saleId()).isEqualTo(saleId);
              assertThat(found.method()).isEqualTo(PaymentMethod.CASH);
              assertThat(found.amount()).isEqualByComparingTo("50.00");
              assertThat(found.tenderedAmount()).isEqualByComparingTo("60.00");
              assertThat(found.changeAmount())
                  .as("troco do dinheiro")
                  .isEqualByComparingTo("10.00");
              assertThat(found.status()).isEqualTo(PaymentStatus.APPROVED);
              assertThat(found.createdByUserId()).isEqualTo(operatorId);
              assertThat(found.createdAt()).isEqualTo(REGISTERED_AT);
              assertThat(found.cancelledAt()).isNull();
            },
            found -> {
              assertThat(found.id()).isEqualTo(pix.id());
              assertThat(found.method()).isEqualTo(PaymentMethod.PIX);
              assertThat(found.amount()).isEqualByComparingTo("12.35");
              assertThat(found.tenderedAmount())
                  .as("fora do dinheiro não há valor entregue (BR-05)")
                  .isNull();
              assertThat(found.changeAmount())
                  .as("sem troco fora do dinheiro (BR-05)")
                  .isEqualByComparingTo("0.00");
              assertThat(found.status()).isEqualTo(PaymentStatus.APPROVED);
              assertThat(found.createdByUserId()).isEqualTo(operatorId);
              assertThat(found.createdAt()).isEqualTo(REGISTERED_AT.plusSeconds(1));
              assertThat(found.cancelledAt()).isNull();
            });

    assertThat(paymentColumn("status", cash.id())).isEqualTo("APPROVED");
    assertThat(paymentColumn("cancelled_at", cash.id())).isNull();
    assertThat(moneyColumn("amount", cash.id())).isEqualByComparingTo("50.00");
    assertThat(moneyColumn("tendered_amount", cash.id())).isEqualByComparingTo("60.00");
    assertThat(moneyColumn("change_amount", cash.id())).isEqualByComparingTo("10.00");
    assertThat(paymentColumn("method", cash.id())).isEqualTo("CASH");
    assertThat(paymentColumn("created_by_user_id", cash.id())).isEqualTo(operatorId);
    assertThat(paymentColumn("created_at", cash.id())).isNotNull();
    assertThat(moneyColumn("tendered_amount", pix.id()))
        .as("valor entregue fica nulo na coluna")
        .isNull();
  }

  @Test
  @Transactional
  @DisplayName("listBySale isola os pagamentos da venda e devolve na ordem de criação")
  void listsOnlyPaymentsOfTheSaleInCreationOrder() {
    Payment firstOfSale = payment(saleId, PaymentMethod.CASH, "10.00", "10.00", REGISTERED_AT);
    Payment onlyOfOtherSale =
        payment(otherSaleId, PaymentMethod.CREDIT, "99.90", null, REGISTERED_AT.plusSeconds(1));
    Payment lastOfSale =
        payment(saleId, PaymentMethod.VOUCHER, "5.00", null, REGISTERED_AT.plusSeconds(2));
    // Insere fora de ordem de propósito: quem ordena é a consulta, não a ordem do insert.
    paymentRepository.insert(lastOfSale);
    paymentRepository.insert(onlyOfOtherSale);
    paymentRepository.insert(firstOfSale);
    flushAndClear();

    assertThat(paymentRepository.listBySale(saleId))
        .as("só os pagamentos da venda, do mais antigo para o mais novo")
        .extracting(Payment::id)
        .containsExactly(firstOfSale.id(), lastOfSale.id());
    assertThat(paymentRepository.listBySale(otherSaleId))
        .extracting(Payment::id)
        .containsExactly(onlyOfOtherSale.id());
    assertThat(paymentRepository.listBySale(UUID.randomUUID()))
        .as("id desconhecido não tem pagamento")
        .isEmpty();
  }

  @Test
  @Transactional
  @DisplayName("sumApprovedBySale soma só os aprovados e devolve zero sem pagamento")
  void sumsOnlyApprovedPayments() {
    assertThat(paymentRepository.sumApprovedBySale(saleId))
        .as("venda sem pagamento soma zero, nunca nulo")
        .isEqualByComparingTo("0.00");
    assertThat(paymentRepository.sumApprovedBySale(otherSaleId)).isEqualByComparingTo("0.00");
    assertThat(paymentRepository.sumApprovedBySale(UUID.randomUUID())).isEqualByComparingTo("0.00");

    Payment approved = payment(saleId, PaymentMethod.CASH, "30.00", "30.00", REGISTERED_AT);
    Payment cancelled =
        payment(saleId, PaymentMethod.PIX, "20.00", null, REGISTERED_AT.plusSeconds(1));
    paymentRepository.insert(approved);
    paymentRepository.insert(cancelled);
    flushAndClear();
    assertThat(paymentRepository.sumApprovedBySale(saleId))
        .as("os dois aprovados somam")
        .isEqualByComparingTo("50.00");

    Payment loaded = paymentRepository.listBySale(saleId).get(1);
    loaded.cancel(REGISTERED_AT.plusSeconds(10));
    paymentRepository.cancel(loaded);
    flushAndClear();

    assertThat(paymentRepository.sumApprovedBySale(saleId))
        .as("cancelado fica de fora da soma (BR-05)")
        .isEqualByComparingTo("30.00");
    assertThat(paymentRepository.sumApprovedBySale(otherSaleId))
        .as("a soma é da venda pedida")
        .isEqualByComparingTo("0.00");
  }

  @Test
  @Transactional
  @DisplayName("cancel leva o pagamento a CANCELLED com o instante gravado e derruba a soma")
  void cancelsPaymentAndDropsFromTheSum() {
    Payment payment = payment(saleId, PaymentMethod.CASH, "20.00", "20.00", REGISTERED_AT);
    paymentRepository.insert(payment);
    flushAndClear();
    assertThat(paymentRepository.sumApprovedBySale(saleId)).isEqualByComparingTo("20.00");

    Instant cancelledAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Payment loaded = paymentRepository.listBySale(saleId).getFirst();
    loaded.cancel(cancelledAt);
    paymentRepository.cancel(loaded);
    flushAndClear();

    assertThat(paymentRepository.listBySale(saleId))
        .satisfiesExactly(
            found -> {
              assertThat(found.status()).isEqualTo(PaymentStatus.CANCELLED);
              assertThat(found.cancelledAt()).isEqualTo(cancelledAt);
              assertThat(found.amount())
                  .as("cancelar não mexe no valor")
                  .isEqualByComparingTo("20.00");
              assertThat(found.tenderedAmount()).isEqualByComparingTo("20.00");
              assertThat(found.changeAmount()).isEqualByComparingTo("0.00");
              assertThat(found.createdByUserId()).isEqualTo(operatorId);
            });
    assertThat(paymentColumn("status", payment.id())).isEqualTo("CANCELLED");
    assertThat(paymentColumn("cancelled_at", payment.id())).isNotNull();
    assertThat(paymentRepository.sumApprovedBySale(saleId))
        .as("cancelado sai da soma")
        .isEqualByComparingTo("0.00");
  }

  /**
   * Pagamento novo no domínio: id UUIDv7 do caso de uso e valor entregue só quando for dinheiro.
   */
  private Payment payment(
      UUID sale, PaymentMethod method, String amount, String tendered, Instant createdAt) {
    return new Payment(
        UuidCreator.getTimeOrderedEpoch(),
        sale,
        method,
        new BigDecimal(amount),
        tendered == null ? null : new BigDecimal(tendered),
        operatorId,
        createdAt);
  }

  /** Grava o que o repositório tem no contexto e limpa: o que o teste lê depois vem do banco. */
  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }

  /** Coluna crua da linha do pagamento: confere o que o mapper escreveu (e o que não escreveu). */
  private Object paymentColumn(String column, UUID paymentId) {
    return entityManager
        .createNativeQuery("select " + column + " from payments where id = :id")
        .setParameter("id", paymentId)
        .getSingleResult();
  }

  /** Coluna monetária crua do pagamento (numeric(14,2)). */
  private BigDecimal moneyColumn(String column, UUID paymentId) {
    return (BigDecimal) paymentColumn(column, paymentId);
  }

  private UUID idByCode(String table, String code) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select id from " + table + " where code = ?")) {
      statement.setString(1, code);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("seed de %s (%s) presente", table, code).isTrue();
        return resultSet.getObject(1, UUID.class);
      }
    }
  }

  /** Usuário das fixtures: o {@code created_by_user_id} do pagamento é FK para {@code users}. */
  private UUID createUser(String username) throws SQLException {
    return insertReturningId(
        "insert into users (id, username, password_hash, display_name, status)"
            + " values (uuidv7(), ?, 'hash', 'Operador de pagamento', 'ACTIVE') returning id",
        username);
  }

  /** Sessão de caixa aberta: a venda das fixtures referencia a sessão. */
  private UUID openSession() throws SQLException {
    return insertReturningId(
        "insert into cash_sessions (id, store_id, cash_register_id, status, opened_by_user_id,"
            + " opened_at, opening_amount)"
            + " values (uuidv7(), ?, ?, 'OPEN', ?, now(), 100.00) returning id",
        storeId,
        registerId,
        operatorId);
  }

  /** Venda aberta sem item: é o pagamento que o teste exercita, não a venda. */
  private UUID createSale(long number) throws SQLException {
    return insertReturningId(
        "insert into sales (id, store_id, number, cash_session_id, cash_register_id,"
            + " operator_user_id, status) values (uuidv7(), ?, ?, ?, ?, ?, 'OPEN') returning id",
        storeId,
        number,
        sessionId,
        registerId,
        operatorId);
  }

  private UUID insertReturningId(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("insert com returning devolve o id").isTrue();
        return resultSet.getObject(1, UUID.class);
      }
    }
  }

  private void deleteIfPresent(String table, UUID id) throws SQLException {
    if (id != null) {
      execute("delete from " + table + " where id = ?", id);
    }
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
}
