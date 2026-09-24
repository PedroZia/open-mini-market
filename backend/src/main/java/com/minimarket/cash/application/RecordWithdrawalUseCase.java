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
 * Registra uma sangria na sessão aberta do caixa (passo 609): dinheiro saindo do caixa com rastro
 * (BR-10). Uma execução = uma transação (§2.2, regra 6) — o movimento do ledger e o evento de
 * auditoria saem juntos ou não saem; falha ao auditar derruba a sangria.
 *
 * <p>O caixa precisa ter sessão aberta: {@link CashSessionStore#findOpenByRegister} vazio é 404
 * {@code CASH_SESSION_NOT_OPEN}, o mesmo código do passo 608 — caixa inexistente, inativo ou já
 * fechado cai no mesmo caso, sem revelar quais caixas existem.
 *
 * <p>Valor nulo ou não positivo e motivo em branco são 400 {@code VALIDATION_ERROR}, na ordem:
 * entrada inválida não chega a consultar o banco. O valor é normalizado na escala 2 com {@code
 * HALF_UP} (§4.4) e gravado <em>negativo</em> no ledger, na convenção de {@code cash_movements}
 * (sangria é saída).
 *
 * <p>O saldo esperado antes e depois sai da regra única do {@link CashSessionAmounts} — a mesma do
 * agregado (passo 605) e da consulta da sessão atual (passo 608), aplicada aos totais que o banco
 * somou ({@link CashSessionStore#sumByType}); a releitura depois do insert já inclui o movimento
 * recém-gravado, sem uma segunda implementação da conta. Sangria acima do esperado <em>não</em>
 * bloqueia: o movimento é registrado e o resultado devolve {@code aboveExpected = true} para o
 * cliente alertar o operador (o dinheiro pode ter entrado por fora do sistema).
 *
 * <p>Auditoria (§7.2): {@code CASH_WITHDRAWAL} na mesma transação, com a sessão em {@code
 * entityId}, o motivo em {@code reason} e {@code amount}, {@code expectedBefore}, {@code
 * expectedAfter} e {@code aboveExpected} em {@code details}. O ator vem do comando — quem o conhece
 * é a API (passo 610), não este caso de uso.
 */
@ApplicationScoped
public class RecordWithdrawalUseCase {

  /** Ação da sangria (§7.2). */
  private static final String CASH_WITHDRAWAL_ACTION = "CASH_WITHDRAWAL";

  /** Alvo do evento: a sessão de caixa de onde o dinheiro saiu. */
  private static final String CASH_SESSION_ENTITY_TYPE = "CASH_SESSION";

  /** Escala do dinheiro (§4.4) e arredondamento das contas do caixa. */
  private static final int SCALE = 2;

  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  @Inject CashSessionStore cashSessionStore;

  /** Auditoria da sangria (§7.2), na transação do movimento. */
  @Inject AuditRecorder auditRecorder;

  /** Relógio da aplicação: o {@code created_at} do movimento, nunca o {@code now()} do banco. */
  @Inject Clock clock;

  /**
   * 400 {@code VALIDATION_ERROR} para valor ausente/não positivo ou motivo em branco; 404 {@code
   * CASH_SESSION_NOT_OPEN} para caixa sem sessão aberta. Devolve o movimento gravado com o esperado
   * antes/depois e o alerta de sangria acima do esperado.
   */
  @Transactional
  public CashMovementResult execute(RecordWithdrawalCommand command) {
    BigDecimal amount = requireAmount(command.amount());
    String reason = requireReason(command.reason());
    CashSessionSummary session = requireOpenSession(command.cashRegisterId());

    Instant createdAt = clock.instant();
    BigDecimal expectedBefore = expectedAmount(session);
    cashSessionStore.insertMovement(
        new NewCashMovement(
            session.storeId(),
            session.id(),
            CashMovementType.WITHDRAWAL,
            amount.negate(),
            null,
            null,
            null,
            reason,
            command.performedByUserId(),
            createdAt));
    BigDecimal expectedAfter = expectedAmount(session);
    boolean aboveExpected = amount.compareTo(expectedBefore) > 0;
    auditRecorder.record(
        CASH_WITHDRAWAL_ACTION,
        CASH_SESSION_ENTITY_TYPE,
        session.id(),
        reason,
        details(amount, expectedBefore, expectedAfter, aboveExpected));

    return new CashMovementResult(
        session.id(),
        CashMovementType.WITHDRAWAL,
        amount,
        reason,
        expectedBefore,
        expectedAfter,
        aboveExpected);
  }

  /** O dinheiro que sai é sempre positivo e entra na escala 2 do projeto (§4.4). */
  private static BigDecimal requireAmount(BigDecimal amount) {
    if (amount == null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "valor da sangria é obrigatório");
    }
    if (amount.signum() <= 0) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "valor da sangria deve ser maior que zero");
    }
    return amount.setScale(SCALE, ROUNDING);
  }

  /** BR-10: sangria sem motivo não é operação auditável. */
  private static String requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "motivo da sangria é obrigatório");
    }
    return reason;
  }

  /** Sem sessão aberta não há de onde sangrar; caixa inexistente e fechado caem no mesmo 404. */
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

  /** O antes/depois mínimo do §7.2: o valor sangrado, o esperado de cada lado e o alerta. */
  private static Map<String, Object> details(
      BigDecimal amount,
      BigDecimal expectedBefore,
      BigDecimal expectedAfter,
      boolean aboveExpected) {
    return Map.of(
        "amount", amount,
        "expectedBefore", expectedBefore,
        "expectedAfter", expectedAfter,
        "aboveExpected", aboveExpected);
  }
}
