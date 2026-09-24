package com.minimarket.catalog.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Map;
import java.util.UUID;

/**
 * Desativa o produto (passo 412) sem apagar histórico: o adaptador grava {@code active = false} e
 * {@code deleted_at}, o que tira o produto da busca padrão, do detalhe e do bipe, e libera o
 * barcode para outro produto — como no índice único parcial (§5.3). Uma execução = uma transação
 * (§2.2, regra 6).
 *
 * <p>Produto que já saiu do catálogo conta como inexistente: id desconhecido, soft-deletado ou já
 * desativado ({@code active} falso) → 404 {@code PRODUCT_NOT_FOUND}, como no disable de usuário
 * (passo 112) e no detalhe (passo 408).
 *
 * <p>Auditoria: a desativação que de fato acontece vira {@code PRODUCT_DISABLED} na mesma
 * transação, com o antes/depois mínimo do {@code active} (§7.2); o 404 não inventa evento, porque
 * nada foi gravado.
 */
@ApplicationScoped
public class DisableProductUseCase {

  /** Ação da desativação (§7.2). */
  private static final String PRODUCT_DISABLED_ACTION = "PRODUCT_DISABLED";

  /** Alvo dos eventos de catálogo (§7.2). */
  private static final String PRODUCT_ENTITY_TYPE = "PRODUCT";

  @Inject ProductStore productStore;

  /** Auditoria da desativação, na transação do caso de uso. */
  @Inject AuditRecorder auditRecorder;

  /**
   * 404 {@code PRODUCT_NOT_FOUND} quando não existe produto vivo e ativo com o id. Devolve o
   * produto já com {@code active = false} e o {@code deleted_at} preenchido.
   */
  @Transactional
  public ProductSummary execute(UUID id) {
    ProductSummary before = requireDisablable(id);
    ProductSummary disabled = productStore.disable(id).orElseThrow(() -> notFound(id));
    auditRecorder.record(
        PRODUCT_DISABLED_ACTION,
        PRODUCT_ENTITY_TYPE,
        id,
        null,
        Map.of(
            "before", Map.of("active", before.active()),
            "after", Map.of("active", disabled.active())));
    return disabled;
  }

  /** Só produto vivo e ativo entra; o que não está no catálogo conta como inexistente. */
  private ProductSummary requireDisablable(UUID id) {
    return productStore
        .findById(id)
        .filter(product -> product.deletedAt() == null && product.active())
        .orElseThrow(() -> notFound(id));
  }

  private static NotFoundException notFound(UUID id) {
    return new NotFoundException(
        ErrorCode.PRODUCT_NOT_FOUND, "produto %s não encontrado".formatted(id));
  }
}
