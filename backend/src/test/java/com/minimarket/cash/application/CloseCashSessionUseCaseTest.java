package com.minimarket.cash.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.cash.domain.CashMovementType;
import com.minimarket.cash.domain.CashSessionStatus;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
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
 * Unitários puros do {@link CloseCashSessionUseCase}, sem Quarkus e sem banco: a porta {@link
 * CashSessionStore} é um dublê escrito à mão e o relógio é fixo, para o fechamento ser conferido
 * campo a campo. O dublê devolve a sessão travada configurada — é ele que encena a corrida do
 * {@code lockById} devolvendo uma sessão já {@code CLOSED} — e registra o que o caso de uso pediu
 * para gravar; a gravação de verdade contra o PostgreSQL é coberta pelo {@code
 * CloseCashSessionIntegrationTest}.
 */
class CloseCashSessionUseCaseTest {

  private static final UUID STORE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000002");
  private static final UUID REGISTER_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000003");
  private static final UUID SESSION_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000004");
  private static final UUID OPERATOR_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000001");
  private static final Instant NOW = Instant.parse("2026-09-24T18:00:00Z");

  private final FakeCashSessionStore cashSessionStore = new FakeCashSessionStore();
  private final FakeAuditRecorder auditRecorder = new FakeAuditRecorder();

  private CloseCashSessionUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new CloseCashSessionUseCase();
    useCase.cashSessionStore = cashSessionStore;
    useCase.auditRecorder = auditRecorder;
    useCase.clock = Clock.fixed(NOW, ZoneOffset.UTC);
  }

  @Test
  @DisplayName(
      "fecha com falta: esperado da regra única, diferença negativa e auditoria da conferência")
  void closesWithShortageAndAuditsTheCount() {
    cashSessionStore.open("100.00");
    cashSessionStore.totals =
        totals(
            Map.of(
                CashMovementType.OPENING, new BigDecimal("100.00"),
                CashMovementType.WITHDRAWAL, new BigDecimal("-30.00")));

    CashSessionSummary closed = useCase.execute(command("60.00", null));

    assertThat(closed.status()).isEqualTo(CashSessionStatus.CLOSED);
    assertThat(closed.countedAmount()).isEqualByComparingTo("60.00");
    assertThat(closed.expectedAmount())
        .as("abertura 100 − sangria 30; o OPENING do ledger não conta duas vezes")
        .isEqualByComparingTo("70.00");
    assertThat(closed.differenceAmount())
        .as("contado − esperado: falta dinheiro")
        .isEqualByComparingTo("-10.00");
    assertThat(closed.closedByUserId()).isEqualTo(OPERATOR_ID);
    assertThat(closed.closedAt()).as("o instante vem do Clock, não do banco").isEqualTo(NOW);
    assertThat(closed.closingNotes()).as("observações são opcionais").isNull();

    CloseCall call = cashSessionStore.closeCall;
    assertThat(call.id()).isEqualTo(SESSION_ID);
    assertThat(call.countedAmount()).isEqualByComparingTo("60.00");
    assertThat(call.countedAmount().scale()).isEqualTo(2);
    assertThat(call.expectedAmount()).isEqualByComparingTo("70.00");
    assertThat(call.differenceAmount()).isEqualByComparingTo("-10.00");
    assertThat(call.closingNotes()).isNull();
    assertThat(call.closedByUserId()).isEqualTo(OPERATOR_ID);
    assertThat(call.closedAt()).isEqualTo(NOW);

    assertThat(cashSessionStore.lookedUpRegisterIds).containsExactly(REGISTER_ID);
    assertThat(cashSessionStore.lockedSessionIds)
        .as("a sessão encontrada é travada antes da conferência")
        .containsExactly(SESSION_ID);
    assertThat(cashSessionStore.summedSessionIds).containsExactly(SESSION_ID);

    Recorded event = auditRecorder.only();
    assertThat(event.action()).isEqualTo("CASH_SESSION_CLOSED");
    assertThat(event.entityType()).isEqualTo("CASH_SESSION");
    assertThat(event.entityId()).isEqualTo(SESSION_ID);
    assertThat(event.reason()).isNull();
    assertThat(event.details())
        .containsOnlyKeys("countedAmount", "expectedAmount", "differenceAmount")
        .containsEntry("countedAmount", new BigDecimal("60.00"))
        .containsEntry("expectedAmount", new BigDecimal("70.00"))
        .containsEntry("differenceAmount", new BigDecimal("-10.00"));
  }

  @Test
  @DisplayName("fecha com sobra: diferença positiva com as observações do operador")
  void closesWithSurplusAndNotes() {
    cashSessionStore.open("100.00");
    cashSessionStore.totals = totals(Map.of(CashMovementType.SUPPLY, new BigDecimal("20.00")));

    CashSessionSummary closed = useCase.execute(command("130.50", "conferido com o gerente"));

    assertThat(closed.expectedAmount()).isEqualByComparingTo("120.00");
    assertThat(closed.differenceAmount()).isEqualByComparingTo("10.50");
    assertThat(closed.closingNotes()).isEqualTo("conferido com o gerente");
    assertThat(cashSessionStore.closeCall.closingNotes()).isEqualTo("conferido com o gerente");
    assertThat(auditRecorder.only().details())
        .containsEntry("expectedAmount", new BigDecimal("120.00"))
        .containsEntry("differenceAmount", new BigDecimal("10.50"));
  }

  @Test
  @DisplayName("valor contado é normalizado na escala 2 com HALF_UP antes da conferência")
  void normalizesCountedAmountToScaleTwoHalfUp() {
    cashSessionStore.open("100.00");

    CashSessionSummary closed = useCase.execute(command("60.005", null));

    assertThat(closed.countedAmount()).isEqualByComparingTo("60.01");
    assertThat(closed.countedAmount().scale()).isEqualTo(2);
    assertThat(closed.differenceAmount()).isEqualByComparingTo("-39.99");
    assertThat(auditRecorder.only().details())
        .containsEntry("countedAmount", new BigDecimal("60.01"));
  }

  @Test
  @DisplayName("valor contado nulo ou negativo é 400 VALIDATION_ERROR sem tocar no banco")
  void rejectsInvalidCountedAmount() {
    for (BigDecimal countedAmount : Arrays.asList(null, new BigDecimal("-0.01"))) {
      assertThatThrownBy(
              () ->
                  useCase.execute(
                      new CloseCashSessionCommand(REGISTER_ID, countedAmount, null, OPERATOR_ID)))
          .isInstanceOfSatisfying(
              BusinessException.class,
              error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    assertThat(cashSessionStore.lookedUpRegisterIds)
        .as("entrada inválida não consulta o caixa")
        .isEmpty();
    assertThat(cashSessionStore.lockedSessionIds).isEmpty();
    assertThat(cashSessionStore.closeCall).isNull();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("caixa sem sessão aberta é 409 CASH_SESSION_ALREADY_CLOSED citando o caixa")
  void rejectsRegisterWithoutOpenSession() {
    assertThatThrownBy(() -> useCase.execute(command("60.00", null)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.CASH_SESSION_ALREADY_CLOSED);
              assertThat(error).hasMessageContaining(REGISTER_ID.toString());
            });

    assertThat(cashSessionStore.lookedUpRegisterIds).containsExactly(REGISTER_ID);
    assertThat(cashSessionStore.lockedSessionIds).as("sem sessão não há o que travar").isEmpty();
    assertThat(cashSessionStore.closeCall).isNull();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName(
      "sessão fechada por outro operador entre a consulta e o lock é 409, sem fechar de novo")
  void rejectsSessionClosedByAnotherOperator() {
    cashSessionStore.open("100.00");
    cashSessionStore.lockedSession = closedSession();

    assertThatThrownBy(() -> useCase.execute(command("60.00", null)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.CASH_SESSION_ALREADY_CLOSED));

    assertThat(cashSessionStore.lockedSessionIds).containsExactly(SESSION_ID);
    assertThat(cashSessionStore.summedSessionIds)
        .as("sessão já fechada nem chega a somar movimentos")
        .isEmpty();
    assertThat(cashSessionStore.closeCall).isNull();
    assertThat(auditRecorder.recorded).isEmpty();

    cashSessionStore.lockedSession = null;

    assertThatThrownBy(() -> useCase.execute(command("60.00", null)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.CASH_SESSION_ALREADY_CLOSED));

    assertThat(cashSessionStore.closeCall).isNull();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  /** Comando do fechamento com o caixa e o operador fixos do cenário. */
  private static CloseCashSessionCommand command(String countedAmount, String closingNotes) {
    return new CloseCashSessionCommand(
        REGISTER_ID, new BigDecimal(countedAmount), closingNotes, OPERATOR_ID);
  }

  /** Sessão como o adaptador a projeta; o dublê a devolve para o caixa {@link #REGISTER_ID}. */
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
   * #REGISTER_ID}) e a sessão travada configurada — nula encena a linha que sumiu, {@code CLOSED}
   * encena a corrida perdida. Os totais vêm do mapa informado e o fechamento vira a projeção que o
   * adaptador devolveria.
   */
  private static final class FakeCashSessionStore implements CashSessionStore {

    private CashSessionSummary openSession;
    private CashSessionSummary lockedSession;
    private Map<CashMovementType, BigDecimal> totals = new EnumMap<>(CashMovementType.class);
    private final List<UUID> lookedUpRegisterIds = new ArrayList<>();
    private final List<UUID> lockedSessionIds = new ArrayList<>();
    private final List<UUID> summedSessionIds = new ArrayList<>();
    private CloseCall closeCall;

    /** Abre a sessão no dublê: a mesma linha volta do find e do lock, como no adaptador. */
    private void open(String openingAmount) {
      openSession = session(CashSessionStatus.OPEN, openingAmount);
      lockedSession = openSession;
    }

    @Override
    public Optional<CashSessionSummary> findOpenByRegister(UUID cashRegisterId) {
      lookedUpRegisterIds.add(cashRegisterId);
      return Optional.ofNullable(openSession)
          .filter(session -> session.cashRegisterId().equals(cashRegisterId));
    }

    @Override
    public Optional<CashSessionSummary> lockById(UUID id) {
      lockedSessionIds.add(id);
      return Optional.ofNullable(lockedSession).filter(session -> session.id().equals(id));
    }

    @Override
    public Map<CashMovementType, BigDecimal> sumByType(UUID cashSessionId) {
      summedSessionIds.add(cashSessionId);
      return new EnumMap<>(totals);
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
      closeCall =
          new CloseCall(
              id,
              countedAmount,
              expectedAmount,
              differenceAmount,
              closingNotes,
              closedByUserId,
              closedAt);
      return new CashSessionSummary(
          id,
          lockedSession.storeId(),
          lockedSession.cashRegisterId(),
          CashSessionStatus.CLOSED,
          lockedSession.openedByUserId(),
          lockedSession.openedAt(),
          lockedSession.openingAmount(),
          closedByUserId,
          closedAt,
          countedAmount,
          expectedAmount,
          differenceAmount,
          closingNotes,
          lockedSession.createdAt(),
          closedAt,
          lockedSession.version() + 1);
    }

    @Override
    public UUID insert(NewCashSession session) {
      throw new UnsupportedOperationException("insert não é usado por CloseCashSession");
    }

    @Override
    public UUID insertMovement(NewCashMovement movement) {
      throw new UnsupportedOperationException("insertMovement não é usado por CloseCashSession");
    }

    @Override
    public Optional<CashSessionSummary> findById(UUID id) {
      throw new UnsupportedOperationException("findById não é usado por CloseCashSession");
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

  /** O fechamento como o caso de uso o entregou à porta. */
  private record CloseCall(
      UUID id,
      BigDecimal countedAmount,
      BigDecimal expectedAmount,
      BigDecimal differenceAmount,
      String closingNotes,
      UUID closedByUserId,
      Instant closedAt) {}

  /** Evento como o caso de uso o entregou ao gravador. */
  private record Recorded(
      String action,
      String entityType,
      UUID entityId,
      String reason,
      Map<String, Object> details) {}
}
