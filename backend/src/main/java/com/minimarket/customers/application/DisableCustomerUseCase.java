package com.minimarket.customers.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Map;
import java.util.UUID;

/**
 * Desativa o cliente (passo 502b) sem apagar histórico: o adaptador grava {@code active = false} e
 * {@code deleted_at}, o que tira o cliente da busca e do detalhe e libera o CPF para outro cliente,
 * como no índice único parcial (§5.3). Uma execução = uma transação (§2.2, regra 6). Não existe
 * reativação de cliente: o desativado nunca volta, então 404 {@code CUSTOMER_NOT_FOUND} cobre o id
 * desconhecido e o já desativado — o mesmo caso, como documenta a porta.
 *
 * <p>Auditoria: a desativação que de fato acontece vira {@code CUSTOMER_DISABLED} na mesma
 * transação, com o antes/depois mínimo do {@code active} (§7.2); o 404 não inventa evento, porque
 * nada foi gravado.
 */
@ApplicationScoped
public class DisableCustomerUseCase {

  /** Ação da desativação (§7.2). */
  private static final String CUSTOMER_DISABLED_ACTION = "CUSTOMER_DISABLED";

  /** Alvo dos eventos de cliente (§7.2). */
  private static final String CUSTOMER_ENTITY_TYPE = "CUSTOMER";

  @Inject CustomerStore customerStore;

  /** Auditoria da desativação, na transação do caso de uso. */
  @Inject AuditRecorder auditRecorder;

  /**
   * 404 {@code CUSTOMER_NOT_FOUND} quando não existe cliente vivo com o id. Devolve o cliente já
   * com {@code active = false} e o {@code deleted_at} preenchido.
   */
  @Transactional
  public CustomerSummary execute(UUID id) {
    CustomerSummary before = requireDisablable(id);
    CustomerSummary disabled = customerStore.disable(id).orElseThrow(() -> notFound(id));
    auditRecorder.record(
        CUSTOMER_DISABLED_ACTION,
        CUSTOMER_ENTITY_TYPE,
        id,
        null,
        Map.of(
            "before", Map.of("active", before.active()),
            "after", Map.of("active", disabled.active())));
    return disabled;
  }

  /** Só cliente vivo entra; o desativado conta como inexistente, como no detalhe. */
  private CustomerSummary requireDisablable(UUID id) {
    return customerStore.findById(id).orElseThrow(() -> notFound(id));
  }

  private static NotFoundException notFound(UUID id) {
    return new NotFoundException(
        ErrorCode.CUSTOMER_NOT_FOUND, "cliente %s não encontrado".formatted(id));
  }
}
