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
 * Reativa o produto desativado (passo 412): o adaptador devolve {@code active = true} e limpa
 * {@code deleted_at}, o que traz o produto de volta à busca padrão e ao bipe. Uma execução = uma
 * transação (§2.2, regra 6).
 *
 * <p>Produto já ativo ({@code active} verdadeiro e {@code deletedAt} nulo) é no-op: responde 200
 * com o produto como está, sem gravar nem auditar — mesmo precedente de {@code EnableUserUseCase} e
 * {@code RevokeSessionUseCase} (passo 310a). Quem foi soft-deletado por outro caminho (o {@code
 * softDelete} da porta, passo 408) também é alcançado: o que decide a reativação é o {@code
 * deletedAt}, não o {@code active}.
 *
 * <p>Se outro produto vivo já tomou o barcode liberado na desativação, o flush do adaptador traduz
 * a violação do índice único parcial em 409 {@code BARCODE_ALREADY_EXISTS} — a transação inteira
 * desfaz e o produto continua desativado.
 *
 * <p>Auditoria: a reativação que de fato acontece vira {@code PRODUCT_ENABLED} na mesma transação,
 * com o antes/depois mínimo do {@code active} (§7.2); o no-op não inventa evento.
 */
@ApplicationScoped
public class EnableProductUseCase {

  /** Ação da reativação (§7.2). */
  private static final String PRODUCT_ENABLED_ACTION = "PRODUCT_ENABLED";

  /** Alvo dos eventos de catálogo (§7.2). */
  private static final String PRODUCT_ENTITY_TYPE = "PRODUCT";

  @Inject ProductStore productStore;

  /** Auditoria da reativação, na transação do caso de uso. */
  @Inject AuditRecorder auditRecorder;

  /**
   * 404 {@code PRODUCT_NOT_FOUND} quando o id não existe; produto desativado (ou soft-deletado) é
   * reativado normalmente. Devolve o produto com {@code active = true} e {@code deleted_at} nulo.
   */
  @Transactional
  public ProductSummary execute(UUID id) {
    ProductSummary current = productStore.findById(id).orElseThrow(() -> notFound(id));
    if (current.active() && current.deletedAt() == null) {
      return current;
    }

    ProductSummary enabled = productStore.enable(id).orElseThrow(() -> notFound(id));
    auditRecorder.record(
        PRODUCT_ENABLED_ACTION,
        PRODUCT_ENTITY_TYPE,
        id,
        null,
        Map.of(
            "before", Map.of("active", current.active()),
            "after", Map.of("active", enabled.active())));
    return enabled;
  }

  private static NotFoundException notFound(UUID id) {
    return new NotFoundException(
        ErrorCode.PRODUCT_NOT_FOUND, "produto %s não encontrado".formatted(id));
  }
}
