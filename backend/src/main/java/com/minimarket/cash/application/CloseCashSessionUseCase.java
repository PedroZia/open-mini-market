package com.minimarket.cash.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.cash.domain.CashSessionAmounts;
import com.minimarket.cash.domain.CashSessionStatus;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Fecha a sessão de caixa com conferência (passo 611): trava a sessão aberta do caixa, calcula o
 * saldo esperado pela regra única do {@link CashSessionAmounts} sobre os movimentos que o banco
 * somou, grava contado, esperado, diferença e observações, e vira {@code CLOSED} — com o evento
 * {@code CASH_SESSION_CLOSED} na mesma transação (§2.2, regra 6; §7.1: falha ao auditar derruba o
 * fechamento).
 *
 * <p>O valor contado é dinheiro informado: nulo ou negativo é 400 {@code VALIDATION_ERROR}, na
 * ordem — entrada inválida não chega a consultar o banco —, normalizado na escala 2 com {@code
 * HALF_UP} (§4.4). A diferença é {@code contado − esperado}: positiva é sobra, negativa é falta; o
 * fechamento <em>não</em> bloqueia por diferença, que é justamente o que a conferência registra.
 *
 * <p>Caixa sem sessão aberta é 409 {@code CASH_SESSION_ALREADY_CLOSED}: fechar um caixa que não
 * está aberto é o mesmo conflito de estado de fechar duas vezes — caixa inexistente, inativo ou já
 * fechado caem no mesmo caso, sem revelar quais caixas existem. A sessão encontrada é então travada
 * com {@link CashSessionStore#lockById} ({@code SELECT ... FOR UPDATE}, passo 604) e o status é
 * rechecado: entre a consulta e o lock, outro operador pode ter fechado a mesma sessão — o segundo
 * recebe o mesmo 409 e nunca fecha duas vezes (§8).
 *
 * <p>Venda {@code OPEN} na sessão bloqueia o fechamento com 409 {@code SESSION_HAS_OPEN_SALES}
 * (passo 909): dinheiro de venda em andamento não está no ledger e fechar agora deixaria o turno
 * com venda pendurada. A checagem vem <em>depois</em> do lock da sessão e antes da conta — a
 * conclusão da venda trava a mesma sessão (passo 906) na mesma ordem, então venda que concluir
 * primeiro não bloqueia (o lock desta transação só sai no fim) e venda que continuar {@code OPEN}
 * bloqueia; o operador conclui ou cancela a venda e fecha de novo. Quem responde pela consulta é a
 * porta invertida {@link SessionSalesLookup}, porque o caixa não depende de {@code sales} (§2.2).
 *
 * <p>Auditoria (§7.2): {@code CASH_SESSION_CLOSED} na mesma transação, com a sessão em {@code
 * entityId} e {@code countedAmount}, {@code expectedAmount} e {@code differenceAmount} em {@code
 * details}. O ator vem do comando — quem o conhece é a API (passo 612), não este caso de uso; o
 * instante do fechamento vem do {@link Clock} injetado, nunca de {@code Instant.now()} espalhado.
 */
@ApplicationScoped
public class CloseCashSessionUseCase {

  /** Ação do fechamento (§7.2). */
  private static final String CASH_SESSION_CLOSED_ACTION = "CASH_SESSION_CLOSED";

  /** Alvo do evento: a sessão de caixa fechada. */
  private static final String CASH_SESSION_ENTITY_TYPE = "CASH_SESSION";

  /** Escala do dinheiro (§4.4) e arredondamento das contas do caixa. */
  private static final int SCALE = 2;

  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  @Inject CashSessionStore cashSessionStore;

  /**
   * Vendas da sessão (passo 909): a porta invertida que o caixa tem para o módulo {@code sales}.
   */
  @Inject SessionSalesLookup sessionSalesLookup;

  /** Auditoria do fechamento (§7.2), na transação da conferência. */
  @Inject AuditRecorder auditRecorder;

  /** Relógio da aplicação: o {@code closed_at} da sessão, nunca o {@code now()} do banco. */
  @Inject Clock clock;

  /**
   * 400 {@code VALIDATION_ERROR} para valor contado ausente ou negativo; 409 {@code
   * CASH_SESSION_ALREADY_CLOSED} para caixa sem sessão aberta ou sessão já fechada; 409 {@code
   * SESSION_HAS_OPEN_SALES} para venda em andamento na sessão. Devolve a sessão fechada com a
   * conferência gravada.
   */
  @Transactional
  public CashSessionSummary execute(CloseCashSessionCommand command) {
    BigDecimal countedAmount = requireCountedAmount(command.countedAmount());
    CashSessionSummary openSession = requireOpenSession(command.cashRegisterId());
    CashSessionSummary lockedSession = lockOpenSession(openSession.id());
    requireNoOpenSales(lockedSession.id());
    BigDecimal expectedAmount = expectedAmount(lockedSession);
    BigDecimal differenceAmount = difference(countedAmount, expectedAmount);
    Instant closedAt = clock.instant();

    CashSessionSummary closedSession =
        cashSessionStore.close(
            lockedSession.id(),
            countedAmount,
            expectedAmount,
            differenceAmount,
            command.closingNotes(),
            command.closedByUserId(),
            closedAt);
    auditRecorder.record(
        CASH_SESSION_CLOSED_ACTION,
        CASH_SESSION_ENTITY_TYPE,
        lockedSession.id(),
        null,
        details(countedAmount, expectedAmount, differenceAmount));

    return closedSession;
  }

  /** O valor contado é obrigatório e não pode ser negativo (§4.4), na escala 2 do projeto. */
  private static BigDecimal requireCountedAmount(BigDecimal countedAmount) {
    if (countedAmount == null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "valor contado é obrigatório");
    }
    if (countedAmount.signum() < 0) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "valor contado não pode ser negativo");
    }
    return countedAmount.setScale(SCALE, ROUNDING);
  }

  /**
   * Sem sessão aberta não há o que fechar; caixa inexistente, inativo e já fechado caem no mesmo
   * 409, o conflito de estado de um segundo fechamento.
   */
  private CashSessionSummary requireOpenSession(UUID cashRegisterId) {
    return cashSessionStore
        .findOpenByRegister(cashRegisterId)
        .orElseThrow(
            () ->
                new ConflictException(
                    ErrorCode.CASH_SESSION_ALREADY_CLOSED,
                    "caixa %s não tem sessão aberta para fechar".formatted(cashRegisterId)));
  }

  /**
   * Trava a linha da sessão ({@code SELECT ... FOR UPDATE}) e recheca o status: o lock é quem
   * serializa dois fechamentos simultâneos (§8) — o perdedor lê a linha já {@code CLOSED} e recebe
   * o mesmo 409 do caminho comum.
   */
  private CashSessionSummary lockOpenSession(UUID sessionId) {
    return cashSessionStore
        .lockById(sessionId)
        .filter(session -> session.status() == CashSessionStatus.OPEN)
        .orElseThrow(
            () ->
                new ConflictException(
                    ErrorCode.CASH_SESSION_ALREADY_CLOSED,
                    "sessão de caixa %s já está fechada".formatted(sessionId)));
  }

  /**
   * Venda {@code OPEN} na sessão é 409 {@code SESSION_HAS_OPEN_SALES} (passo 909). A checagem vem
   * depois do lock da sessão: a conclusão da venda trava a mesma sessão na transação dela (passo
   * 906), então o lock decide a corrida — venda que concluir primeiro não bloqueia, venda que ficar
   * {@code OPEN} bloqueia. A consulta é da porta invertida {@link SessionSalesLookup}: o módulo
   * {@code cash} não conhece o banco da venda (§2.2).
   */
  private void requireNoOpenSales(UUID sessionId) {
    if (sessionSalesLookup.existsOpenByCashSession(sessionId)) {
      throw new ConflictException(
          ErrorCode.SESSION_HAS_OPEN_SALES,
          "sessão de caixa %s tem venda em andamento".formatted(sessionId));
    }
  }

  /**
   * Saldo esperado da sessão pela regra única do {@link CashSessionAmounts} sobre os totais
   * assinados que o banco somou; o {@code OPENING} do ledger não entra duas vezes porque a abertura
   * já vem em {@code openingAmount}.
   */
  private BigDecimal expectedAmount(CashSessionSummary session) {
    return CashSessionAmounts.expectedAmount(
        session.openingAmount(), cashSessionStore.sumByType(session.id()));
  }

  /** Contado − esperado (§4.4): positivo é sobra, negativo é falta; sempre na escala 2. */
  private static BigDecimal difference(BigDecimal countedAmount, BigDecimal expectedAmount) {
    return countedAmount.subtract(expectedAmount).setScale(SCALE, ROUNDING);
  }

  /** A conferência do §7.2: o contado, o esperado e a diferença — o essencial do fechamento. */
  private static Map<String, Object> details(
      BigDecimal countedAmount, BigDecimal expectedAmount, BigDecimal differenceAmount) {
    return Map.of(
        "countedAmount", countedAmount,
        "expectedAmount", expectedAmount,
        "differenceAmount", differenceAmount);
  }
}
