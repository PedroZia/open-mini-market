package com.minimarket.sales.application;

import com.github.f4b6a3.uuid.UuidCreator;
import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.cash.application.CashSessionStore;
import com.minimarket.cash.application.CashSessionSummary;
import com.minimarket.sales.domain.Sale;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.ForbiddenException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Abre a venda (passo 805): a venda nasce {@code OPEN}, vazia e com os totais zerados, no caixa da
 * sessão de caixa aberta, com o número sequencial da loja (passo 804) e auditoria {@code
 * SALE_CREATED}. Uma execução = uma transação (§2.2, regra 6): número, linha da venda e evento saem
 * juntos ou não saem — o número volta para a série quando algo falha depois de alocado, porque a
 * alocação participa desta transação.
 *
 * <p>BR-06: venda só existe dentro de sessão de caixa aberta — a checagem de {@link
 * CashSessionStore#findOpenByRegister} vem antes de qualquer gravação e caixa fechado é 409 {@code
 * CASH_SESSION_REQUIRED} (não é 404: o caixa existe, o que falta é a sessão aberta). BR-11: o
 * operador opera no caixa ao qual a sessão autenticada está vinculada; o vínculo chega pronto no
 * comando, lido pela API (passo 807) do {@code OperationContext} — comando sem caixa é 403 {@code
 * ACCESS_DENIED}.
 *
 * <p>A loja não vem do comando: é a configurada ({@code minimarket.store.default-code}), como nos
 * demais cadastros — o MVP tem loja única (§5.3). O instante da abertura vem do {@link Clock}
 * injetado (o mesmo do login e da abertura do caixa), nunca de {@code Instant.now()} espalhado no
 * código.
 *
 * <p>Auditoria (§7.2): a abertura vira {@code SALE_CREATED} na mesma transação, com a venda em
 * {@code entityId} e número, sessão de caixa e caixa em {@code details}. O caso de uso devolve o
 * agregado criado — quem monta a resposta é a API (passo 807).
 */
@ApplicationScoped
public class CreateSaleUseCase {

  /** Ação da venda aberta (§7.2). */
  private static final String SALE_CREATED_ACTION = "SALE_CREATED";

  /** Alvo do evento de abertura: a própria venda. */
  private static final String SALE_ENTITY_TYPE = "SALE";

  @Inject CashSessionStore cashSessionStore;

  @Inject SaleStore saleStore;

  /** Número sequencial da loja (passo 804), na transação da abertura. */
  @Inject SaleNumberAllocator saleNumberAllocator;

  @Inject StoreLookup storeLookup;

  /** Auditoria da abertura (§7.2), na transação da criação. */
  @Inject AuditRecorder auditRecorder;

  /** Relógio da aplicação: {@code created_at} da venda. */
  @Inject Clock clock;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  /**
   * 403 {@code ACCESS_DENIED} para sessão sem caixa vinculado; 409 {@code CASH_SESSION_REQUIRED}
   * quando o caixa não tem sessão aberta.
   */
  @Transactional
  public Sale execute(CreateSaleCommand command) {
    UUID cashRegisterId = requireBoundRegister(command.cashRegisterId());
    UUID cashSessionId = requireOpenSession(cashRegisterId);
    UUID storeId = currentStoreId();
    long number = saleNumberAllocator.nextNumber(storeId);
    Sale sale =
        new Sale(
            UuidCreator.getTimeOrderedEpoch(),
            storeId,
            number,
            cashSessionId,
            cashRegisterId,
            command.operatorUserId(),
            null,
            clock.instant());
    saleStore.insert(sale);
    auditRecorder.record(
        SALE_CREATED_ACTION, SALE_ENTITY_TYPE, sale.id(), null, details(sale), cashSessionId);

    return sale;
  }

  /**
   * Sem vínculo de caixa não há onde vender (BR-11): a sessão autenticada precisa ter aberto o
   * caixa antes. É 403 — a identidade é válida, o que falta é o vínculo.
   */
  private static UUID requireBoundRegister(UUID cashRegisterId) {
    if (cashRegisterId == null) {
      throw new ForbiddenException(
          "sessão autenticada sem caixa vinculado: abra o caixa antes de vender");
    }
    return cashRegisterId;
  }

  /** Venda só existe dentro de sessão de caixa aberta (BR-06). */
  private UUID requireOpenSession(UUID cashRegisterId) {
    return cashSessionStore
        .findOpenByRegister(cashRegisterId)
        .map(CashSessionSummary::id)
        .orElseThrow(
            () ->
                new ConflictException(
                    ErrorCode.CASH_SESSION_REQUIRED,
                    "nenhuma sessão de caixa aberta para o caixa %s".formatted(cashRegisterId)));
  }

  /** Venda só existe dentro de uma loja; a loja atual vem da configuração, não do comando. */
  private UUID currentStoreId() {
    return storeLookup
        .findByCode(defaultStoreCode)
        .orElseThrow(
            () -> new IllegalStateException("loja configurada não existe: " + defaultStoreCode))
        .id();
  }

  /** Details mínimo do evento: a série da venda e o caixa/sessão que a abriu. */
  private static Map<String, Object> details(Sale sale) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("number", sale.number());
    details.put("cashSessionId", sale.cashSessionId());
    details.put("cashRegisterId", sale.cashRegisterId());
    return details;
  }
}
