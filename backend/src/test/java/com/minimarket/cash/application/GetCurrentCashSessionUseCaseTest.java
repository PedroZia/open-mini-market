package com.minimarket.cash.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.cash.domain.CashMovementType;
import com.minimarket.cash.domain.CashSessionStatus;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link GetCurrentCashSessionUseCase}, sem Quarkus e sem banco: a porta {@link
 * CashSessionStore} é um dublê escrito à mão e a resposta é conferida campo a campo. O saldo
 * esperado é calculado pela mesma regra do {@code CashSessionAmounts} (testada no domínio); aqui se
 * prova que o caso de uso a aplica sobre os totais do banco, zero-preenchendo os quatro tipos.
 */
class GetCurrentCashSessionUseCaseTest {

  private static final UUID REGISTER_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000002");
  private static final UUID SESSION_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000003");
  private static final UUID OPERATOR_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000001");
  private static final Instant OPENED_AT = Instant.parse("2026-09-24T13:00:00Z");

  private final FakeCashSessionStore cashSessionStore = new FakeCashSessionStore();

  private GetCurrentCashSessionUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new GetCurrentCashSessionUseCase();
    useCase.cashSessionStore = cashSessionStore;
  }

  @Test
  @DisplayName("caixa sem sessão aberta lança 404 CASH_SESSION_NOT_OPEN citando o caixa")
  void rejectsRegisterWithoutOpenSession() {
    assertThatThrownBy(() -> useCase.execute(REGISTER_ID))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.CASH_SESSION_NOT_OPEN);
              assertThat(error).hasMessageContaining(REGISTER_ID.toString());
            });

    assertThat(cashSessionStore.summedSessionId).as("sem sessão não há o que somar").isNull();
  }

  @Test
  @DisplayName(
      "sessão recém-aberta: esperado é a abertura e só o OPENING do ledger aparece nos totais")
  void reportsOpeningWithoutDoubleCounting() {
    cashSessionStore.openSession = openSession("150.00");
    cashSessionStore.totals = totals(Map.of(CashMovementType.OPENING, new BigDecimal("150.00")));

    CurrentCashSessionView view = useCase.execute(REGISTER_ID);

    assertThat(view.sessionId()).isEqualTo(SESSION_ID);
    assertThat(view.cashRegisterId()).isEqualTo(REGISTER_ID);
    assertThat(view.status()).isEqualTo(CashSessionStatus.OPEN);
    assertThat(view.openedAt()).isEqualTo(OPENED_AT);
    assertThat(view.openedByUserId()).isEqualTo(OPERATOR_ID);
    assertThat(view.openingAmount()).isEqualByComparingTo("150.00");
    assertThat(view.expectedAmount())
        .as("o OPENING do ledger não soma de novo sobre a abertura")
        .isEqualByComparingTo("150.00");
    assertThat(view.totalsByType())
        .containsOnlyKeys(
            CashMovementType.OPENING,
            CashMovementType.SALE,
            CashMovementType.SUPPLY,
            CashMovementType.WITHDRAWAL)
        .containsEntry(CashMovementType.OPENING, new BigDecimal("150.00"))
        .containsEntry(CashMovementType.SALE, new BigDecimal("0.00"))
        .containsEntry(CashMovementType.SUPPLY, new BigDecimal("0.00"))
        .containsEntry(CashMovementType.WITHDRAWAL, new BigDecimal("0.00"));
    assertThat(cashSessionStore.summedSessionId).isEqualTo(SESSION_ID);
  }

  @Test
  @DisplayName(
      "totais do banco entram assinados: esperado = abertura + venda + suprimento − sangria")
  void combinesTotalsFromStore() {
    cashSessionStore.openSession = openSession("100.00");
    cashSessionStore.totals =
        totals(
            Map.of(
                CashMovementType.OPENING, new BigDecimal("100.00"),
                CashMovementType.SALE, new BigDecimal("50.25"),
                CashMovementType.SUPPLY, new BigDecimal("20.00"),
                CashMovementType.WITHDRAWAL, new BigDecimal("-30.10")));

    CurrentCashSessionView view = useCase.execute(REGISTER_ID);

    assertThat(view.expectedAmount()).isEqualByComparingTo("140.15");
    assertThat(view.totalsByType())
        .containsEntry(CashMovementType.SALE, new BigDecimal("50.25"))
        .containsEntry(CashMovementType.SUPPLY, new BigDecimal("20.00"))
        .containsEntry(CashMovementType.WITHDRAWAL, new BigDecimal("-30.10"));
  }

  @Test
  @DisplayName("caixa de outro id não casa: o dublê devolve a sessão pela chave pedida")
  void looksUpSessionByRequestedRegister() {
    cashSessionStore.openSession = openSession("10.00");
    cashSessionStore.totals = totals(Map.of());

    assertThatThrownBy(() -> useCase.execute(UUID.randomUUID()))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.CASH_SESSION_NOT_OPEN));
  }

  /** Sessão aberta como o adaptador a projeta; fechamento ainda nulo. */
  private static CashSessionSummary openSession(String openingAmount) {
    return new CashSessionSummary(
        SESSION_ID,
        UUID.fromString("0199a2b3-0000-7000-8000-000000000004"),
        REGISTER_ID,
        CashSessionStatus.OPEN,
        OPERATOR_ID,
        OPENED_AT,
        new BigDecimal(openingAmount),
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

  /** Totais como o banco os somaria, já assinados por tipo. */
  private static Map<CashMovementType, BigDecimal> totals(
      Map<CashMovementType, BigDecimal> entries) {
    Map<CashMovementType, BigDecimal> totals = new EnumMap<>(CashMovementType.class);
    totals.putAll(entries);
    return totals;
  }

  /**
   * Dublê de {@link CashSessionStore}: devolve a sessão aberta configurada (só para o caixa {@link
   * #REGISTER_ID}) e os totais informados, gravando o id consultado em {@code sumByType}.
   */
  private static final class FakeCashSessionStore implements CashSessionStore {

    private CashSessionSummary openSession;
    private Map<CashMovementType, BigDecimal> totals = new EnumMap<>(CashMovementType.class);
    private UUID summedSessionId;

    @Override
    public Optional<CashSessionSummary> findOpenByRegister(UUID cashRegisterId) {
      return Optional.ofNullable(openSession)
          .filter(session -> session.cashRegisterId().equals(cashRegisterId));
    }

    @Override
    public Map<CashMovementType, BigDecimal> sumByType(UUID cashSessionId) {
      summedSessionId = cashSessionId;
      return totals;
    }

    @Override
    public UUID insert(NewCashSession session) {
      throw new UnsupportedOperationException("insert não é usado por GetCurrentCashSession");
    }

    @Override
    public Optional<CashSessionSummary> findById(UUID id) {
      throw new UnsupportedOperationException("findById não é usado por GetCurrentCashSession");
    }

    @Override
    public UUID insertMovement(NewCashMovement movement) {
      throw new UnsupportedOperationException(
          "insertMovement não é usado por GetCurrentCashSession");
    }

    @Override
    public Optional<CashSessionSummary> lockById(UUID id) {
      throw new UnsupportedOperationException("lockById não é usado por GetCurrentCashSession");
    }
  }
}
