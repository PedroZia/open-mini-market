package com.minimarket.cash.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.cash.domain.CashMovementType;
import com.minimarket.cash.domain.CashSessionAmounts;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
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
 * Registra um suprimento na sessão aberta do caixa (passo 610): dinheiro entrando no caixa com
 * rastro (BR-10). Espelha o {@link RecordWithdrawalUseCase} (passo 609) — uma execução = uma
 * transação (§2.2, regra 6): o movimento do ledger e o evento de auditoria saem juntos ou não saem;
 * falha ao auditar derruba o suprimento.
 *
 * <p>O caixa precisa ter sessão aberta: {@link CashSessionStore#findOpenByRegister} vazio é 404
 * {@code CASH_SESSION_NOT_OPEN}, o mesmo código do passo 608 — caixa inexistente, inativo ou já
 * fechado cai no mesmo caso, sem revelar quais caixas existem.
 *
 * <p>Valor nulo ou não positivo e motivo em branco são 400 {@code VALIDATION_ERROR}, na ordem:
 * entrada inválida não chega a consultar o banco. O valor é normalizado na escala 2 com {@code
 * HALF_UP} (§4.4) e gravado <em>positivo</em> no ledger, na convenção de {@code cash_movements}
 * (suprimento é entrada).
 *
 * <p>O saldo esperado antes e depois sai da regra única do {@link CashSessionAmounts} — a mesma do
 * agregado (passo 605) e da consulta da sessão atual (passo 608), aplicada aos totais que o banco
 * somou ({@link CashSessionStore#sumByType}); a releitura depois do insert já inclui o movimento
 * recém-gravado, sem uma segunda implementação da conta. O resultado devolve {@code aboveExpected =
 * false} sempre: o alerta de "valor acima do esperado" é só da sangria (passo 609) — suprimento
 * acima do esperado é dinheiro que <em>entrou</em>, não há o que alertar.
 *
 * <p>Auditoria (§7.2): {@code CASH_SUPPLY} na mesma transação, com a sessão em {@code entityId}, o
 * motivo em {@code reason} e {@code amount}, {@code expectedBefore} e {@code expectedAfter} em
 * {@code details} — sem {@code aboveExpected}, que é do evento da sangria. O ator vem do comando —
 * quem o conhece é a API (passo 610), não este caso de uso.
 */
@ApplicationScoped
public class RecordSupplyUseCase {

  /** Ação do suprimento (§7.2). */
  private static final String CASH_SUPPLY_ACTION = "CASH_SUPPLY";

  /** Alvo do evento: a sessão de caixa em que o dinheiro entrou. */
  private static final String CASH_SESSION_ENTITY_TYPE = "CASH_SESSION";

  /** Escala do dinheiro (§4.4) e arredondamento das contas do caixa. */
  private static final int SCALE = 2;

  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  @Inject CashSessionStore cashSessionStore;

  /** Auditoria do suprimento (§7.2), na transação do movimento. */
  @Inject AuditRecorder auditRecorder;

  /** Relógio da aplicação: o {@code created_at} do movimento, nunca o {@code now()} do banco. */
  @Inject Clock clock;

  /**
   * 400 {@code VALIDATION_ERROR} para valor ausente/não positivo ou motivo em branco; 404 {@code
   * CASH_SESSION_NOT_OPEN} para caixa sem sessão aberta. Devolve o movimento gravado com o esperado
   * antes/depois do suprimento.
   */
  @Transactional
  public CashMovementResult execute(RecordSupplyCommand command) {
    BigDecimal amount = requireAmount(command.amount());
    String reason = requireReason(command.reason());
    CashSessionSummary session = requireOpenSession(command.cashRegisterId());

    Instant createdAt = clock.instant();
    BigDecimal expectedBefore = expectedAmount(session);
    cashSessionStore.insertMovement(
        new NewCashMovement(
            session.storeId(),
            session.id(),
            CashMovementType.SUPPLY,
            amount,
            null,
            null,
            null,
            reason,
            command.performedByUserId(),
            createdAt));
    BigDecimal expectedAfter = expectedAmount(session);
    auditRecorder.record(
        CASH_SUPPLY_ACTION,
        CASH_SESSION_ENTITY_TYPE,
        session.id(),
        reason,
        details(amount, expectedBefore, expectedAfter));

    return new CashMovementResult(
        session.id(),
        CashMovementType.SUPPLY,
        amount,
        reason,
        expectedBefore,
        expectedAfter,
        false);
  }

  /** O dinheiro que entra é sempre positivo e entra na escala 2 do projeto (§4.4). */
  private static BigDecimal requireAmount(BigDecimal amount) {
    if (amount == null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "valor do suprimento é obrigatório");
    }
    if (amount.signum() <= 0) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "valor do suprimento deve ser maior que zero");
    }
    return amount.setScale(SCALE, ROUNDING);
  }

  /** BR-10: suprimento sem motivo não é operação auditável. */
  private static String requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "motivo do suprimento é obrigatório");
    }
    return reason;
  }

  /** Sem sessão aberta não há onde suprir; caixa inexistente e fechado caem no mesmo 404. */
  private CashSessionSummary requireOpenSession(UUID cashRegisterId) {
    return cashSessionStore
        .findOpenByRegister(cashRegisterId)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCode.CASH_SESSION_NOT_OPEN,
                    "caixa %s não tem sessão aberta".formatted(cashRegisterId)));
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

  /** O antes/depois mínimo do §7.2: o valor suprido e o esperado de cada lado. */
  private static Map<String, Object> details(
      BigDecimal amount, BigDecimal expectedBefore, BigDecimal expectedAfter) {
    return Map.of(
        "amount", amount, "expectedBefore", expectedBefore, "expectedAfter", expectedAfter);
  }
}
