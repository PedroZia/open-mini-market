package com.minimarket.cash.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.cash.domain.CashMovementType;
import com.minimarket.cash.domain.CashSessionStatus;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link GetCashSessionSummaryUseCase}, sem Quarkus e sem banco: as portas
 * {@link CashSessionStore} e {@link SessionSalesLookup} são dublês escritos à mão e o resumo é
 * conferido campo a campo. O esperado é calculado pela mesma regra do {@code CashSessionAmounts}
 * (testada no domínio); aqui se prova que o caso de uso a aplica sobre os totais do banco —
 * zero-preenchendo os quatro tipos — e repassa a quebra por forma de pagamento da porta invertida
 * sem mexer nela (passo 909).
 */
class GetCashSessionSummaryUseCaseTest {

  private static final UUID STORE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000002");
  private static final UUID REGISTER_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000003");
  private static final UUID SESSION_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000004");
  private static final UUID OPERATOR_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000001");
  private static final Instant OPENED_AT = Instant.parse("2026-09-24T13:00:00Z");

  private final FakeCashSessionStore cashSessionStore = new FakeCashSessionStore();
  private final FakeSessionSalesLookup sessionSalesLookup = new FakeSessionSalesLookup();

  private GetCashSessionSummaryUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new GetCashSessionSummaryUseCase();
    useCase.cashSessionStore = cashSessionStore;
    useCase.sessionSalesLookup = sessionSalesLookup;
  }

  @Test
  @DisplayName("sessão desconhecida é 404 CASH_SESSION_NOT_FOUND sem consultar as vendas")
  void rejectsUnknownSession() {
    assertThatThrownBy(() -> useCase.execute(SESSION_ID))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.CASH_SESSION_NOT_FOUND));

    assertThat(cashSessionStore.summedSessionIds).as("sem sessão não há o que somar").isEmpty();
    assertThat(sessionSalesLookup.lookedUpSessionIds)
        .as("sem sessão não há vendas a consultar")
        .isEmpty();
  }

  @Test
  @DisplayName(
      "aberta: esperado da regra única, totais zero-preenchidos e a quebra por forma repassada")
  void summarizesOpenSessionWithPaymentsByMethod() {
    cashSessionStore.session = session(CashSessionStatus.OPEN);
    cashSessionStore.totals =
        totals(
            Map.of(
                CashMovementType.OPENING, new BigDecimal("100.00"),
                CashMovementType.SALE, new BigDecimal("30.00"),
                CashMovementType.WITHDRAWAL, new BigDecimal("-25.00")));
    Map<String, BigDecimal> byMethod = byMethod("30.00", "10.00");
    sessionSalesLookup.paymentsByMethod = byMethod;

    CashSessionSummaryView view = useCase.execute(SESSION_ID);

    assertThat(view.sessionId()).isEqualTo(SESSION_ID);
    assertThat(view.status()).isEqualTo(CashSessionStatus.OPEN);
    assertThat(view.openingAmount()).isEqualByComparingTo("100.00");
    assertThat(view.expectedAmount())
        .as("100 de abertura + 30 em dinheiro − 25 de sangria")
        .isEqualByComparingTo("105.00");
    assertThat(view.countedAmount()).as("nulo enquanto aberta").isNull();
    assertThat(view.differenceAmount()).as("nulo enquanto aberta").isNull();
    assertThat(view.totalsByType())
        .containsOnlyKeys(CashMovementType.values())
        .containsEntry(CashMovementType.OPENING, new BigDecimal("100.00"))
        .containsEntry(CashMovementType.SALE, new BigDecimal("30.00"))
        .containsEntry(CashMovementType.SUPPLY, new BigDecimal("0.00"))
        .containsEntry(CashMovementType.WITHDRAWAL, new BigDecimal("-25.00"));
    assertThat(view.paymentsByMethod())
        .as("a quebra sai da porta invertida sem o caso de uso mexer nela")
        .isEqualTo(byMethod);
    assertThat(cashSessionStore.summedSessionIds).containsExactly(SESSION_ID);
    assertThat(sessionSalesLookup.lookedUpSessionIds).containsExactly(SESSION_ID);
  }

  @Test
  @DisplayName("fechada: contado e diferença da sessão com a mesma conta de esperado")
  void reportsClosedSessionConference() {
    cashSessionStore.session = closedSession("105.00", "105.00");
    cashSessionStore.totals = totals(Map.of(CashMovementType.SALE, new BigDecimal("5.00")));
    sessionSalesLookup.paymentsByMethod = byMethod("5.00", "0.00");

    CashSessionSummaryView view = useCase.execute(SESSION_ID);

    assertThat(view.status()).isEqualTo(CashSessionStatus.CLOSED);
    assertThat(view.expectedAmount()).isEqualByComparingTo("105.00");
    assertThat(view.countedAmount()).isEqualByComparingTo("105.00");
    assertThat(view.differenceAmount()).isEqualByComparingTo("0.00");
  }

  /** Sessão aberta como o adaptador a projeta; fechamento ainda nulo. */
  private static CashSessionSummary session(CashSessionStatus status) {
    return new CashSessionSummary(
        SESSION_ID,
        STORE_ID,
        REGISTER_ID,
        status,
        OPERATOR_ID,
        OPENED_AT,
        new BigDecimal("100.00"),
        null,
        null,
        null,
        null,
        null,
        null,
        OPENED_AT,
        OPENED_AT,
        0L);
  }

  /** Sessão fechada com a conferência gravada, como o adaptador a projeta. */
  private static CashSessionSummary closedSession(String countedAmount, String expectedAmount) {
    return new CashSessionSummary(
        SESSION_ID,
        STORE_ID,
        REGISTER_ID,
        CashSessionStatus.CLOSED,
        OPERATOR_ID,
        OPENED_AT,
        new BigDecimal("100.00"),
        OPERATOR_ID,
        OPENED_AT,
        new BigDecimal(countedAmount),
        new BigDecimal(expectedAmount),
        new BigDecimal(countedAmount).subtract(new BigDecimal(expectedAmount)),
        null,
        OPENED_AT,
        OPENED_AT,
        1L);
  }

  /** Totais como o banco os somaria, já assinados por tipo. */
  private static Map<CashMovementType, BigDecimal> totals(
      Map<CashMovementType, BigDecimal> entries) {
    Map<CashMovementType, BigDecimal> totals = new EnumMap<>(CashMovementType.class);
    totals.putAll(entries);
    return totals;
  }

  /** Quebra por forma como o adaptador a devolve: as cinco sempre presentes, na ordem do enum. */
  private static Map<String, BigDecimal> byMethod(String cash, String credit) {
    Map<String, BigDecimal> byMethod = new LinkedHashMap<>();
    byMethod.put("CASH", new BigDecimal(cash));
    byMethod.put("PIX", new BigDecimal("0.00"));
    byMethod.put("DEBIT", new BigDecimal("0.00"));
    byMethod.put("CREDIT", new BigDecimal(credit));
    byMethod.put("VOUCHER", new BigDecimal("0.00"));
    return byMethod;
  }

  /**
   * Dublê de {@link CashSessionStore}: devolve a sessão configurada (por id) e os totais
   * informados, gravando os ids consultados.
   */
  private static final class FakeCashSessionStore implements CashSessionStore {

    private CashSessionSummary session;
    private Map<CashMovementType, BigDecimal> totals = new EnumMap<>(CashMovementType.class);
    private final List<UUID> summedSessionIds = new ArrayList<>();

    @Override
    public Optional<CashSessionSummary> findById(UUID id) {
      return Optional.ofNullable(session).filter(found -> found.id().equals(id));
    }

    @Override
    public Map<CashMovementType, BigDecimal> sumByType(UUID cashSessionId) {
      summedSessionIds.add(cashSessionId);
      return new EnumMap<>(totals);
    }

    @Override
    public UUID insert(NewCashSession session) {
      throw new UnsupportedOperationException("insert não é usado por GetCashSessionSummary");
    }

    @Override
    public Optional<CashSessionSummary> findOpenByRegister(UUID cashRegisterId) {
      throw new UnsupportedOperationException(
          "findOpenByRegister não é usado por GetCashSessionSummary");
    }

    @Override
    public UUID insertMovement(NewCashMovement movement) {
      throw new UnsupportedOperationException(
          "insertMovement não é usado por GetCashSessionSummary");
    }

    @Override
    public Optional<CashSessionSummary> lockById(UUID id) {
      throw new UnsupportedOperationException("lockById não é usado por GetCashSessionSummary");
    }

    @Override
    public CashSessionSummary close(
        UUID id,
        BigDecimal countedAmount,
        BigDecimal expectedAmount,
        BigDecimal differenceAmount,
        String closingNotes,
        UUID closedByUserId,
        Instant closedAt) {
      throw new UnsupportedOperationException("close não é usado por GetCashSessionSummary");
    }
  }

  /**
   * Dublê de {@link SessionSalesLookup}: devolve a quebra configurada e grava os ids consultados —
   * é por ele que o teste confere que a porta só é chamada depois de a sessão existir.
   */
  private static final class FakeSessionSalesLookup implements SessionSalesLookup {

    private Map<String, BigDecimal> paymentsByMethod = Map.of();
    private final List<UUID> lookedUpSessionIds = new ArrayList<>();

    @Override
    public boolean existsOpenByCashSession(UUID cashSessionId) {
      throw new UnsupportedOperationException(
          "existsOpenByCashSession não é usado por GetCashSessionSummary");
    }

    @Override
    public Map<String, BigDecimal> sumApprovedPaymentsByMethod(UUID cashSessionId) {
      lookedUpSessionIds.add(cashSessionId);
      return paymentsByMethod;
    }
  }
}
