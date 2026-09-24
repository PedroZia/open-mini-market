package com.minimarket.cash.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.cash.domain.CashMovementType;
import com.minimarket.cash.domain.CashSessionStatus;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link RecordWithdrawalUseCase}, sem Quarkus e sem banco: a porta {@link
 * CashSessionStore} é um dublê escrito à mão e o relógio é fixo, para o movimento ser conferido
 * campo a campo. O dublê imita o auto-flush do Hibernate (o movimento inserido passa a contar nos
 * totais), que é o que faz o esperado depois incluir a sangria recém-gravada — a gravação de
 * verdade contra o PostgreSQL é coberta pelo {@code RecordWithdrawalIntegrationTest}.
 */
class RecordWithdrawalUseCaseTest {

  private static final UUID STORE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000002");
  private static final UUID REGISTER_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000003");
  private static final UUID SESSION_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000004");
  private static final UUID OPERATOR_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000001");
  private static final Instant NOW = Instant.parse("2026-09-24T14:00:00Z");

  private final FakeCashSessionStore cashSessionStore = new FakeCashSessionStore();
  private final FakeAuditRecorder auditRecorder = new FakeAuditRecorder();

  private RecordWithdrawalUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new RecordWithdrawalUseCase();
    useCase.cashSessionStore = cashSessionStore;
    useCase.auditRecorder = auditRecorder;
    useCase.clock = Clock.fixed(NOW, ZoneOffset.UTC);
  }

  @Test
  @DisplayName(
      "sangra a sessão aberta: movimento negativo com motivo e ator, auditoria com o antes/depois")
  void recordsNegativeMovementAndAuditsBeforeAndAfter() {
    cashSessionStore.openSession = openSession("100.00");
    cashSessionStore.totals =
        totals(
            Map.of(
                CashMovementType.OPENING, new BigDecimal("100.00"),
                CashMovementType.SALE, new BigDecimal("40.00")));

    CashMovementResult result = useCase.execute(command("60.00", "depósito bancário"));

    assertThat(result.sessionId()).isEqualTo(SESSION_ID);
    assertThat(result.type()).isEqualTo(CashMovementType.WITHDRAWAL);
    assertThat(result.amount())
        .as("o resultado mostra o valor informado, positivo")
        .isEqualByComparingTo("60.00");
    assertThat(result.reason()).isEqualTo("depósito bancário");
    assertThat(result.expectedBefore())
        .as("abertura 100 + venda 40; o OPENING do ledger não conta duas vezes")
        .isEqualByComparingTo("140.00");
    assertThat(result.expectedAfter()).isEqualByComparingTo("80.00");
    assertThat(result.aboveExpected()).isFalse();

    NewCashMovement movement = cashSessionStore.movement;
    assertThat(movement.type()).isEqualTo(CashMovementType.WITHDRAWAL);
    assertThat(movement.amount())
        .as("sangria entra negativa no ledger")
        .isEqualByComparingTo("-60.00");
    assertThat(movement.amount().scale()).isEqualTo(2);
    assertThat(movement.storeId()).as("a loja vem da sessão, não do comando").isEqualTo(STORE_ID);
    assertThat(movement.cashSessionId()).isEqualTo(SESSION_ID);
    assertThat(movement.reason()).isEqualTo("depósito bancário");
    assertThat(movement.createdByUserId()).isEqualTo(OPERATOR_ID);
    assertThat(movement.createdAt()).isEqualTo(NOW);
    assertThat(movement.paymentMethod()).isNull();
    assertThat(movement.referenceType()).isNull();
    assertThat(movement.referenceId()).isNull();

    assertThat(cashSessionStore.lockedSessionIds)
        .as("o status é rechecado sob o lock da sessão, antes de gravar o movimento")
        .containsExactly(SESSION_ID);
    assertThat(cashSessionStore.summedSessionIds)
        .as("o esperado antes e depois usa a mesma regra sobre os totais do banco")
        .containsExactly(SESSION_ID, SESSION_ID);

    Recorded event = auditRecorder.only();
    assertThat(event.action()).isEqualTo("CASH_WITHDRAWAL");
    assertThat(event.entityType()).isEqualTo("CASH_SESSION");
    assertThat(event.entityId()).isEqualTo(SESSION_ID);
    assertThat(event.reason())
        .as("o motivo é o rastro humano da sangria")
        .isEqualTo("depósito bancário");
    assertThat(event.details())
        .containsOnlyKeys("amount", "expectedBefore", "expectedAfter", "aboveExpected")
        .containsEntry("amount", new BigDecimal("60.00"))
        .containsEntry("expectedBefore", new BigDecimal("140.00"))
        .containsEntry("expectedAfter", new BigDecimal("80.00"))
        .containsEntry("aboveExpected", false);
  }

  @Test
  @DisplayName(
      "sangria acima do esperado não bloqueia: grava o movimento e devolve acima do esperado")
  void alertsWithoutBlockingWhenAmountExceedsExpected() {
    cashSessionStore.openSession = openSession("20.00");

    CashMovementResult result = useCase.execute(command("50.00", "pagamento de fornecedor"));

    assertThat(result.expectedBefore()).isEqualByComparingTo("20.00");
    assertThat(result.expectedAfter()).isEqualByComparingTo("-30.00");
    assertThat(result.aboveExpected()).isTrue();
    assertThat(cashSessionStore.movement.amount()).isEqualByComparingTo("-50.00");
    assertThat(auditRecorder.only().details())
        .containsEntry("expectedAfter", new BigDecimal("-30.00"))
        .containsEntry("aboveExpected", true);
  }

  @Test
  @DisplayName("sangria igual ao esperado esvazia o caixa sem alerta: não é maior que o esperado")
  void doesNotAlertWhenAmountEqualsExpected() {
    cashSessionStore.openSession = openSession("40.00");

    CashMovementResult result = useCase.execute(command("40.00", "troca de turno"));

    assertThat(result.expectedAfter()).isEqualByComparingTo("0.00");
    assertThat(result.aboveExpected()).isFalse();
    assertThat(cashSessionStore.movement.amount()).isEqualByComparingTo("-40.00");
  }

  @Test
  @DisplayName("valor nulo, zero ou negativo é 400 VALIDATION_ERROR sem tocar no banco")
  void rejectsInvalidAmount() {
    for (BigDecimal amount : Arrays.asList(null, new BigDecimal("0"), new BigDecimal("-0.01"))) {
      assertThatThrownBy(
              () ->
                  useCase.execute(
                      new RecordWithdrawalCommand(REGISTER_ID, amount, "motivo", OPERATOR_ID)))
          .isInstanceOfSatisfying(
              BusinessException.class,
              error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    assertThat(cashSessionStore.lookedUpRegisterIds)
        .as("entrada inválida não consulta o caixa")
        .isEmpty();
    assertThat(cashSessionStore.movement).isNull();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("motivo nulo ou em branco é 400 VALIDATION_ERROR sem tocar no banco")
  void rejectsBlankReason() {
    for (String reason : Arrays.asList(null, "", "   ")) {
      assertThatThrownBy(
              () ->
                  useCase.execute(
                      new RecordWithdrawalCommand(
                          REGISTER_ID, new BigDecimal("10.00"), reason, OPERATOR_ID)))
          .isInstanceOfSatisfying(
              BusinessException.class,
              error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    assertThat(cashSessionStore.lookedUpRegisterIds).isEmpty();
    assertThat(cashSessionStore.movement).isNull();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("caixa sem sessão aberta é 404 CASH_SESSION_NOT_OPEN citando o caixa, sem gravar")
  void rejectsRegisterWithoutOpenSession() {
    assertThatThrownBy(() -> useCase.execute(command("10.00", "depósito bancário")))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.CASH_SESSION_NOT_OPEN);
              assertThat(error).hasMessageContaining(REGISTER_ID.toString());
            });

    assertThat(cashSessionStore.lookedUpRegisterIds).containsExactly(REGISTER_ID);
    assertThat(cashSessionStore.movement).isNull();
    assertThat(auditRecorder.recorded).isEmpty();

    cashSessionStore.openSession = openSession("100.00");
    UUID otherRegister = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                useCase.execute(
                    new RecordWithdrawalCommand(
                        otherRegister, new BigDecimal("10.00"), "depósito bancário", OPERATOR_ID)))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.CASH_SESSION_NOT_OPEN));

    assertThat(cashSessionStore.movement)
        .as("sessão de outro caixa não serve para sangrar")
        .isNull();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("sessão fechada entre a leitura e o lock: 404 CASH_SESSION_NOT_OPEN sem gravar")
  void rejectsSessionClosedUnderTheLock() {
    cashSessionStore.openSession = openSession("100.00");
    cashSessionStore.lockedSession = closedSession();

    assertThatThrownBy(() -> useCase.execute(command("10.00", "depósito bancário")))
        .as("o fechamento da outra transação vence a corrida e a sangria não grava fora da conta")
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.CASH_SESSION_NOT_OPEN));

    assertThat(cashSessionStore.lockedSessionIds).containsExactly(SESSION_ID);
    assertThat(cashSessionStore.movement).isNull();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("valor é normalizado na escala 2 com HALF_UP antes de virar movimento")
  void normalizesAmountToScaleTwoHalfUp() {
    cashSessionStore.openSession = openSession("100.00");

    CashMovementResult result = useCase.execute(command("10.555", "ajuste de troco"));

    assertThat(result.amount()).isEqualByComparingTo("10.56");
    assertThat(cashSessionStore.movement.amount()).isEqualByComparingTo("-10.56");
    assertThat(cashSessionStore.movement.amount().scale()).isEqualTo(2);
    assertThat(result.expectedAfter()).isEqualByComparingTo("89.44");
    assertThat(auditRecorder.only().details()).containsEntry("amount", new BigDecimal("10.56"));
  }

  /** Comando da sangria com o caixa e o operador fixos do cenário. */
  private static RecordWithdrawalCommand command(String amount, String reason) {
    return new RecordWithdrawalCommand(REGISTER_ID, new BigDecimal(amount), reason, OPERATOR_ID);
  }

  /** Sessão aberta como o adaptador a projeta; fechamento ainda nulo. */
  private static CashSessionSummary openSession(String openingAmount) {
    return session(CashSessionStatus.OPEN, openingAmount);
  }

  /** Sessão já fechada pelo vencedor da corrida, como o {@code lockById} a releria. */
  private static CashSessionSummary closedSession() {
    return session(CashSessionStatus.CLOSED, "100.00");
  }

  private static CashSessionSummary session(CashSessionStatus status, String openingAmount) {
    return new CashSessionSummary(
        SESSION_ID,
        STORE_ID,
        REGISTER_ID,
        status,
        OPERATOR_ID,
        NOW,
        new BigDecimal(openingAmount),
        null,
        null,
        null,
        null,
        null,
        null,
        NOW,
        NOW,
        0L);
  }

  /** Totais como o banco os somaria, já assinados por tipo. */
  private static Map<CashMovementType, BigDecimal> totals(
      Map<CashMovementType, BigDecimal> entries) {
    Map<CashMovementType, BigDecimal> totals = new EnumMap<>(CashMovementType.class);
    totals.putAll(entries);
    return totals;
  }

  /**
   * Dublê de {@link CashSessionStore}: devolve a sessão aberta configurada (só para o caixa {@link
   * #REGISTER_ID}) e a sessão travada configurada — {@code CLOSED} encena a corrida perdida para o
   * fechamento. Soma os totais somando o movimento inserido, como o banco faria na releitura depois
   * do insert.
   */
  private static final class FakeCashSessionStore implements CashSessionStore {

    private CashSessionSummary openSession;
    private CashSessionSummary lockedSession;
    private Map<CashMovementType, BigDecimal> totals = new EnumMap<>(CashMovementType.class);
    private final List<UUID> lookedUpRegisterIds = new ArrayList<>();
    private final List<UUID> lockedSessionIds = new ArrayList<>();
    private final List<UUID> summedSessionIds = new ArrayList<>();
    private NewCashMovement movement;

    @Override
    public Optional<CashSessionSummary> findOpenByRegister(UUID cashRegisterId) {
      lookedUpRegisterIds.add(cashRegisterId);
      return Optional.ofNullable(openSession)
          .filter(session -> session.cashRegisterId().equals(cashRegisterId));
    }

    @Override
    public Map<CashMovementType, BigDecimal> sumByType(UUID cashSessionId) {
      summedSessionIds.add(cashSessionId);
      return new EnumMap<>(totals);
    }

    @Override
    public UUID insertMovement(NewCashMovement newMovement) {
      movement = newMovement;
      totals.merge(newMovement.type(), newMovement.amount(), BigDecimal::add);
      return UUID.randomUUID();
    }

    @Override
    public UUID insert(NewCashSession session) {
      throw new UnsupportedOperationException("insert não é usado por RecordWithdrawal");
    }

    @Override
    public Optional<CashSessionSummary> findById(UUID id) {
      throw new UnsupportedOperationException("findById não é usado por RecordWithdrawal");
    }

    @Override
    public Optional<CashSessionSummary> lockById(UUID id) {
      lockedSessionIds.add(id);
      CashSessionSummary session = lockedSession != null ? lockedSession : openSession;
      return Optional.ofNullable(session).filter(locked -> locked.id().equals(id));
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
      throw new UnsupportedOperationException("close não é usado por RecordWithdrawal");
    }
  }

  /**
   * Dublê de {@link AuditRecorder}: guarda o que o caso de uso pediu para gravar, sem CDI e sem
   * banco. A subclasse só sobrescreve {@code record} — o caminho de verdade (contexto + INSERT) é
   * do passo 303 e tem teste próprio contra PostgreSQL.
   */
  private static final class FakeAuditRecorder extends AuditRecorder {

    private final List<Recorded> recorded = new ArrayList<>();

    @Override
    public void record(
        String action,
        String entityType,
        UUID entityId,
        String reason,
        Map<String, Object> details) {
      recorded.add(new Recorded(action, entityType, entityId, reason, details));
    }

    /** Único evento do cenário; o teste falha se o caso de uso gravou zero ou dois. */
    private Recorded only() {
      assertThat(recorded).as("eventos de auditoria do cenário").hasSize(1);
      return recorded.getFirst();
    }
  }

  /** Evento como o caso de uso o entregou ao gravador. */
  private record Recorded(
      String action,
      String entityType,
      UUID entityId,
      String reason,
      Map<String, Object> details) {}
}
