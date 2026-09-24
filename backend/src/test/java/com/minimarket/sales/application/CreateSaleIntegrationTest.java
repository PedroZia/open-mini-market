package com.minimarket.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.minimarket.IntegrationTestBase;
import com.minimarket.cash.application.OpenCashSessionCommand;
import com.minimarket.cash.application.OpenCashSessionUseCase;
import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleStatus;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.ConflictException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link CreateSaleUseCase} contra PostgreSQL real (Dev Services), no caixa {@code
 * CAIXA-01} do seed da V11 — critério de aceite do passo 805.
 *
 * <p>Sem {@code @TestTransaction}: o caso de uso comita de verdade, como na API — o teste confere o
 * que ficou no banco por SQL (linha da venda, itens e evento) e limpa tudo o que comitou ao final,
 * na ordem que as FKs exigem: itens → venda → eventos → série → movimentos → sessão de caixa →
 * usuário. A sessão de caixa é aberta pelo {@code OpenCashSessionUseCase} (o caminho real, com o
 * movimento {@code OPENING} e o evento dele), e o operador e a loja saem das portas, em transação
 * própria — o mesmo recurso do {@code OpenCashSessionIntegrationTest}.
 *
 * <p>A série da venda ({@code document_sequences}) é apagada antes e depois de cada teste: ela
 * nasce na primeira alocação e o banco é compartilhado com os demais testes do fork, então nenhum
 * teste pode herdar o contador do anterior.
 */
@QuarkusTest
class CreateSaleIntegrationTest extends IntegrationTestBase {

  private static final String OPERATOR = "vendas.create.805";

  /** Série da venda em {@code document_sequences}; a NFC-e da Fase 14 terá a própria. */
  private static final String SALE_DOC_TYPE = "SALE";

  @Inject CreateSaleUseCase useCase;

  @Inject OpenCashSessionUseCase openCashSessionUseCase;

  @Inject UserStore userStore;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  private UUID storeId;

  private UUID userId;

  private UUID cashSessionId;

  private final List<UUID> saleIds = new ArrayList<>();

  @Test
  @DisplayName(
      "abre a venda no CAIXA-01 com sessão real: linha OPEN vazia na sales e evento SALE_CREATED")
  void opensSaleInOpenCashSession() throws SQLException {
    UUID store = storeId();
    userId = newOperator();
    UUID register = cashRegisterId("CAIXA-01");
    openCashSession(register);

    Sale sale = useCase.execute(new CreateSaleCommand(register, userId));
    saleIds.add(sale.id());

    assertThat(sale.id().version()).as("id da venda é UUIDv7").isEqualTo(7);
    assertThat(sale.status()).isEqualTo(SaleStatus.OPEN);
    assertThat(sale.storeId()).isEqualTo(store);
    assertThat(sale.number()).as("primeira venda da série da loja").isEqualTo(1L);
    assertThat(sale.cashSessionId()).isEqualTo(cashSessionId);
    assertThat(sale.cashRegisterId()).isEqualTo(register);
    assertThat(sale.operatorUserId()).isEqualTo(userId);
    assertThat(sale.notes()).isNull();
    assertThat(sale.customerId()).as("cliente é do passo 811").isNull();
    assertThat(sale.items()).isEmpty();
    assertThat(sale.total()).isEqualByComparingTo("0.00");

    SaleRow stored = saleRow(sale.id());
    assertThat(stored.status()).isEqualTo("OPEN");
    assertThat(stored.number()).isEqualTo(1L);
    assertThat(stored.storeId()).isEqualTo(store);
    assertThat(stored.cashSessionId()).isEqualTo(cashSessionId);
    assertThat(stored.cashRegisterId()).isEqualTo(register);
    assertThat(stored.operatorUserId()).isEqualTo(userId);
    assertThat(stored.customerId()).isNull();
    assertThat(stored.subtotal()).isEqualTo("0.00");
    assertThat(stored.discountAmount()).isEqualTo("0.00");
    assertThat(stored.total()).isEqualTo("0.00");
    assertThat(stored.paidAmount()).isEqualTo("0.00");
    assertThat(stored.changeAmount()).isEqualTo("0.00");
    assertThat(stored.itemCount()).isZero();
    assertThat(stored.notes()).isNull();
    assertThat(stored.completedAt()).isNull();
    assertThat(stored.version()).isZero();
    assertThat(stored.createdAt())
        .as("created_at vem do relógio da aplicação, como no agregado devolvido")
        .isCloseTo(sale.createdAt(), within(1, ChronoUnit.SECONDS));
    assertThat(itemCountOf(sale.id())).as("venda nasce vazia: nenhum item").isZero();

    Event event = eventOf(sale.id());
    assertThat(event.action()).isEqualTo("SALE_CREATED");
    assertThat(event.entityType()).isEqualTo("SALE");
    assertThat(event.source()).as("sem request HTTP o evento é de sistema").isEqualTo("SYSTEM");
    assertThat(event.actorUserId()).isNull();
    assertThat(event.number()).isEqualTo("1");
    assertThat(event.cashSessionId()).isEqualTo(cashSessionId.toString());
    assertThat(event.cashRegisterId()).isEqualTo(register.toString());
  }

  @Test
  @DisplayName("caixa sem sessão aberta recusa com 409 CASH_SESSION_REQUIRED sem gravar venda")
  void rejectsRegisterWithoutOpenSession() throws SQLException {
    userId = newOperator();
    UUID register = cashRegisterId("CAIXA-01");
    long salesBefore = saleCountOf(register);

    assertThatThrownBy(() -> useCase.execute(new CreateSaleCommand(register, userId)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.CASH_SESSION_REQUIRED));

    assertThat(saleCountOf(register)).as("nenhuma venda gravada na recusa").isEqualTo(salesBefore);
  }

  @Test
  @DisplayName("duas vendas no mesmo caixa aberto recebem números distintos e consecutivos")
  void allocatesDistinctNumbers() throws SQLException {
    userId = newOperator();
    UUID register = cashRegisterId("CAIXA-01");
    openCashSession(register);
    long salesBefore = saleCountOf(register);

    Sale first = useCase.execute(new CreateSaleCommand(register, userId));
    Sale second = useCase.execute(new CreateSaleCommand(register, userId));
    saleIds.add(first.id());
    saleIds.add(second.id());

    assertThat(first.number()).isEqualTo(1L);
    assertThat(second.number()).as("a série avança a cada venda").isEqualTo(2L);
    assertThat(first.id()).isNotEqualTo(second.id());
    assertThat(saleRow(first.id()).number()).isEqualTo(1L);
    assertThat(saleRow(second.id()).number()).isEqualTo(2L);
    assertThat(saleCountOf(register)).as("duas vendas a mais no caixa").isEqualTo(salesBefore + 2);
  }

  /** Operador de verdade: o {@code operator_user_id} da venda tem FK para {@code users}. */
  private UUID newOperator() {
    return callInOwnTransaction(
        () -> userStore.insert(new NewUser(OPERATOR, "Operador de venda", "hash", "ACTIVE")));
  }

  /** Abre o CAIXA-01 pelo caminho real: a venda só existe em sessão de caixa aberta (BR-06). */
  private void openCashSession(UUID register) {
    cashSessionId =
        openCashSessionUseCase
            .execute(new OpenCashSessionCommand(register, new BigDecimal("100.00"), userId, null))
            .id();
  }

  /** Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}. */
  @AfterEach
  void removeCommittedRows() throws SQLException {
    for (UUID saleId : saleIds) {
      execute("delete from sale_items where sale_id = ?", saleId);
      execute("delete from sales where id = ?", saleId);
    }
    for (UUID saleId : saleIds) {
      execute("delete from audit_events where entity_id = ?", saleId);
    }
    execute("delete from audit_events where entity_id = ?", cashSessionId);
    deleteSaleSequence();
    execute("delete from cash_movements where cash_session_id = ?", cashSessionId);
    execute("delete from cash_sessions where id = ?", cashSessionId);
    execute("delete from users where id = ?", userId);
  }

  /** A série nasce na primeira alocação: nenhum teste pode herdar o contador do anterior. */
  @BeforeEach
  void resetSaleSequence() throws SQLException {
    deleteSaleSequence();
  }

  private void deleteSaleSequence() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "delete from document_sequences where store_id = ? and doc_type = ?")) {
      statement.setObject(1, storeId());
      statement.setString(2, SALE_DOC_TYPE);
      statement.executeUpdate();
    }
  }

  /** Venda como o banco a guardou. */
  private SaleRow saleRow(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select status, number, store_id, cash_session_id, cash_register_id,"
                    + " operator_user_id, customer_id, subtotal::text as subtotal,"
                    + " discount_amount::text as discount_amount, total::text as total,"
                    + " paid_amount::text as paid_amount, change_amount::text as change_amount,"
                    + " item_count, notes, created_at, completed_at, version"
                    + " from sales where id = ?")) {
      statement.setObject(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("venda %s gravada", id).isTrue();
        return new SaleRow(
            resultSet.getString("status"),
            resultSet.getLong("number"),
            resultSet.getObject("store_id", UUID.class),
            resultSet.getObject("cash_session_id", UUID.class),
            resultSet.getObject("cash_register_id", UUID.class),
            resultSet.getObject("operator_user_id", UUID.class),
            resultSet.getObject("customer_id", UUID.class),
            resultSet.getString("subtotal"),
            resultSet.getString("discount_amount"),
            resultSet.getString("total"),
            resultSet.getString("paid_amount"),
            resultSet.getString("change_amount"),
            resultSet.getInt("item_count"),
            resultSet.getString("notes"),
            resultSet.getTimestamp("created_at").toInstant(),
            resultSet.getObject("completed_at"),
            resultSet.getLong("version"));
      }
    }
  }

  /** Quantos itens a venda tem no banco; a venda do 805 nasce vazia. */
  private long itemCountOf(UUID saleId) throws SQLException {
    return count("select count(*) from sale_items where sale_id = ?", saleId);
  }

  /** Quantas vendas o caixa tem no banco, para a recusa não deixar linha nova. */
  private long saleCountOf(UUID cashRegisterId) throws SQLException {
    return count("select count(*) from sales where cash_register_id = ?", cashRegisterId);
  }

  private long count(String sql, UUID parameter) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, parameter);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).isTrue();
        return resultSet.getLong(1);
      }
    }
  }

  /** Evento de auditoria da venda; {@code details} vem extraído por chave do jsonb. */
  private Event eventOf(UUID entityId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select action, entity_type, source, actor_user_id, details->>'number' as number,"
                    + " details->>'cashSessionId' as cash_session_id, details->>'cashRegisterId' as"
                    + " cash_register_id from audit_events where entity_id = ?")) {
      statement.setObject(1, entityId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("evento gravado para a venda %s", entityId).isTrue();
        return new Event(
            resultSet.getString("action"),
            resultSet.getString("entity_type"),
            resultSet.getString("source"),
            resultSet.getObject("actor_user_id", UUID.class),
            resultSet.getString("number"),
            resultSet.getString("cash_session_id"),
            resultSet.getString("cash_register_id"));
      }
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

  /** Linha de {@code sales} como o banco a guardou. */
  private record SaleRow(
      String status,
      long number,
      UUID storeId,
      UUID cashSessionId,
      UUID cashRegisterId,
      UUID operatorUserId,
      UUID customerId,
      String subtotal,
      String discountAmount,
      String total,
      String paidAmount,
      String changeAmount,
      int itemCount,
      String notes,
      Instant createdAt,
      Object completedAt,
      long version) {}

  /** Linha de {@code audit_events} como o banco a guardou. */
  private record Event(
      String action,
      String entityType,
      String source,
      UUID actorUserId,
      String number,
      String cashSessionId,
      String cashRegisterId) {}
}
