package com.minimarket.cash.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.auth.application.AuthSessionStore;
import com.minimarket.cash.domain.CashMovementType;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Abre o caixa (passo 606): valida o caixa ativo, a ausência de sessão aberta e o fundo de troco,
 * cria a sessão, grava o movimento {@code OPENING} e audita {@code CASH_SESSION_OPENED}. Uma
 * execução = uma transação (§2.2, regra 6) — sessão, movimento, vínculo da sessão autenticada e
 * evento saem juntos ou não saem.
 *
 * <p>O fundo de troco é dinheiro informado: nulo ou negativo é 400 {@code VALIDATION_ERROR}, com o
 * arredondamento {@code HALF_UP} da escala 2 do projeto. Caixa inexistente <em>ou</em> inativo é o
 * mesmo 404 {@code CASH_REGISTER_NOT_FOUND} — o operador não escolhe caixa desativado (§9.3). Caixa
 * já aberto é 409 {@code CASH_REGISTER_ALREADY_OPEN}: a checagem é do caso de uso e o índice único
 * parcial {@code ux_cash_session_open} é o backstop da corrida entre dois operadores (§8) — o
 * adaptador traduz a violação para o mesmo 409.
 *
 * <p>A loja não vem do comando: é a configurada ({@code minimarket.store.default-code}), como nos
 * demais cadastros — o MVP tem loja única (§5.3). O instante da abertura e o do movimento vêm do
 * {@link Clock} injetado (o mesmo do login), nunca de {@code Instant.now()} espalhado no código.
 *
 * <p>O caso de uso não conhece o {@code OperationContext}: quem sabe o ator da requisição é a API
 * (passo 607), que lê o id da sessão autenticada e o repassa no comando; o vínculo da sessão ao
 * caixa ({@link AuthSessionStore#bindCashRegister}) é gravado aqui, na mesma transação, quando há
 * sessão — {@code cash.application → auth.application} não fecha ciclo de módulos.
 *
 * <p>Auditoria (§7.2): a abertura vira {@code CASH_SESSION_OPENED} na mesma transação, com a sessão
 * criada em {@code entityId} e o caixa e o valor de abertura em {@code details}.
 */
@ApplicationScoped
public class OpenCashSessionUseCase {

  /** Ação da sessão de caixa aberta (§7.2). */
  private static final String CASH_SESSION_OPENED_ACTION = "CASH_SESSION_OPENED";

  /** Alvo do evento de abertura: a própria sessão de caixa. */
  private static final String CASH_SESSION_ENTITY_TYPE = "CASH_SESSION";

  /** Escala do dinheiro (§4.4) e arredondamento das contas do caixa. */
  private static final int SCALE = 2;

  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  @Inject CashRegisterStore cashRegisterStore;

  @Inject CashSessionStore cashSessionStore;

  @Inject StoreLookup storeLookup;

  /** Vínculo da sessão autenticada com o caixa aberto (passo 607), na transação da abertura. */
  @Inject AuthSessionStore authSessionStore;

  /** Auditoria da abertura (§7.2), na transação da criação. */
  @Inject AuditRecorder auditRecorder;

  /** Relógio da aplicação: {@code opened_at} da sessão e {@code created_at} do movimento. */
  @Inject Clock clock;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  /**
   * 400 {@code VALIDATION_ERROR} para valor de abertura ausente ou negativo; 404 {@code
   * CASH_REGISTER_NOT_FOUND} para caixa inexistente ou inativo; 409 {@code
   * CASH_REGISTER_ALREADY_OPEN} quando o caixa já tem sessão aberta.
   */
  @Transactional
  public CashSessionSummary execute(OpenCashSessionCommand command) {
    BigDecimal openingAmount = requireOpeningAmount(command.openingAmount());
    UUID cashRegisterId = requireActiveRegister(command.cashRegisterId());
    requireNoOpenSession(cashRegisterId);

    UUID storeId = currentStoreId();
    Instant openedAt = clock.instant();
    UUID sessionId =
        cashSessionStore.insert(
            new NewCashSession(
                storeId, cashRegisterId, command.openedByUserId(), openedAt, openingAmount));
    cashSessionStore.insertMovement(
        new NewCashMovement(
            storeId,
            sessionId,
            CashMovementType.OPENING,
            openingAmount,
            null,
            null,
            null,
            null,
            command.openedByUserId(),
            openedAt));
    bindAuthSession(command.authSessionId(), cashRegisterId);
    auditRecorder.record(
        CASH_SESSION_OPENED_ACTION,
        CASH_SESSION_ENTITY_TYPE,
        sessionId,
        null,
        details(cashRegisterId, openingAmount));

    return storedSession(sessionId);
  }

  /** O fundo de troco é zero ou positivo, normalizado à escala 2 na convenção do projeto (§4.4). */
  private static BigDecimal requireOpeningAmount(BigDecimal openingAmount) {
    if (openingAmount == null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "valor de abertura é obrigatório");
    }
    if (openingAmount.signum() < 0) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "valor de abertura não pode ser negativo");
    }
    return openingAmount.setScale(SCALE, ROUNDING);
  }

  /** Caixa desativado é indistinguível de caixa inexistente para quem tenta abrir (§9.3). */
  private UUID requireActiveRegister(UUID cashRegisterId) {
    return cashRegisterStore
        .findActiveById(cashRegisterId)
        .map(CashRegisterSummary::id)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCode.CASH_REGISTER_NOT_FOUND,
                    "caixa %s não encontrado ou inativo".formatted(cashRegisterId)));
  }

  /**
   * Um caixa aberto por vez (§8): a checagem evita o INSERT condenado, e o índice único parcial da
   * tabela é quem garante a unicidade se dois operadores passarem por aqui ao mesmo tempo.
   */
  private void requireNoOpenSession(UUID cashRegisterId) {
    Optional<CashSessionSummary> open = cashSessionStore.findOpenByRegister(cashRegisterId);
    if (open.isPresent()) {
      throw new ConflictException(
          ErrorCode.CASH_REGISTER_ALREADY_OPEN,
          "caixa %s já está aberto pela sessão %s".formatted(cashRegisterId, open.get().id()));
    }
  }

  /**
   * A sessão autenticada passa a apontar para o caixa aberto: é o vínculo que a auditoria das
   * operações seguintes usa para dizer de qual caixa o dinheiro saiu (passos 607+). Sem sessão
   * autenticada (fora de requisição HTTP) não há o que vincular.
   */
  private void bindAuthSession(UUID authSessionId, UUID cashRegisterId) {
    if (authSessionId != null) {
      authSessionStore.bindCashRegister(authSessionId, cashRegisterId);
    }
  }

  /** A sessão recém-inserida, com os defaults que o banco completou; o insert commita junto. */
  private CashSessionSummary storedSession(UUID id) {
    return cashSessionStore
        .findById(id)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "sessão de caixa %s não encontrada após o insert".formatted(id)));
  }

  /** Sessão só existe dentro de uma loja; a loja atual vem da configuração, não do comando. */
  private UUID currentStoreId() {
    return storeLookup
        .findByCode(defaultStoreCode)
        .orElseThrow(
            () -> new IllegalStateException("loja configurada não existe: " + defaultStoreCode))
        .id();
  }

  /** Details mínimo do evento: o caixa aberto e o fundo de troco informado. */
  private static Map<String, Object> details(UUID cashRegisterId, BigDecimal openingAmount) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("cashRegisterId", cashRegisterId);
    details.put("openingAmount", openingAmount);
    return details;
  }
}
