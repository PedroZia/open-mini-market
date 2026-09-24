package com.minimarket.catalog.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;

/**
 * Altera o preço do produto com motivo (passo 411). Uma execução = uma transação (§2.2, regra 6): a
 * leitura do preço atual, as validações, a gravação e o evento de auditoria vivem juntas — falha ao
 * auditar derruba a alteração. O adaptador ainda traduz o stale do Hibernate como backstop caso
 * outra requisição grave o produto entre a leitura e o flush.
 *
 * <p>Só entram produto vivo e ativo: {@code deletedAt} preenchido ou {@code active} falso é 404
 * {@code PRODUCT_NOT_FOUND}, como no detalhe (passo 408) e na edição (passo 410).
 *
 * <p>O motivo é obrigatório (preço mudando sem justificativa não é operação auditável) e o preço é
 * normalizado em escala 2 com {@code HALF_UP}, como no cadastro (passo 405). Preço igual ao atual é
 * no-op e responde 200 com o produto: não há alteração a gravar nem evento a inventar — mesmo
 * precedente de {@code EnableUserUseCase} e {@code RevokeSessionUseCase} (passo 310a).
 *
 * <p>Auditoria: a alteração que de fato acontece vira {@code PRODUCT_PRICE_CHANGED} na mesma
 * transação, com o motivo em {@code reason} e o antes/depois mínimo do preço (§7.2) — o "before" é
 * lido antes de gravar, porque depois do update a projeção já viria com o preço novo.
 */
@ApplicationScoped
public class ChangeProductPriceUseCase {

  /** Ação da alteração de preço (§7.2). */
  private static final String PRODUCT_PRICE_CHANGED_ACTION = "PRODUCT_PRICE_CHANGED";

  /** Alvo dos eventos de catálogo (§7.2). */
  private static final String PRODUCT_ENTITY_TYPE = "PRODUCT";

  @Inject ProductStore productStore;

  /** Auditoria do preço (passo 411), na transação da alteração. */
  @Inject AuditRecorder auditRecorder;

  /**
   * 404 {@code PRODUCT_NOT_FOUND} para id desconhecido, produto soft-deletado ou desativado; 400
   * {@code VALIDATION_ERROR} para preço ausente/negativo ou motivo em branco. Preço igual ao atual
   * é no-op: devolve o produto como está, sem gravar nem auditar. Devolve o produto com o preço
   * novo e o {@code version} avançado pelo lock otimista.
   */
  @Transactional
  public ProductSummary execute(ChangeProductPriceCommand command) {
    ProductSummary before = requireEditable(command.id());
    BigDecimal price = requireValidPrice(command.price());
    String reason = requireReason(command.reason());
    if (before.price().compareTo(price) == 0) {
      return before;
    }

    ProductSummary after =
        productStore.updatePrice(command.id(), price).orElseThrow(() -> notFound(command.id()));
    auditRecorder.record(
        PRODUCT_PRICE_CHANGED_ACTION,
        PRODUCT_ENTITY_TYPE,
        command.id(),
        reason,
        beforeAndAfter(before.price(), after.price()));
    return after;
  }

  /** Produto vivo e ativo; o que não está no catálogo conta como inexistente, como no passo 410. */
  private ProductSummary requireEditable(UUID id) {
    return productStore
        .findById(id)
        .filter(product -> product.deletedAt() == null && product.active())
        .orElseThrow(() -> notFound(id));
  }

  /** Dinheiro entra com duas casas (§4.4), arredondando como as contas de venda. */
  private static BigDecimal requireValidPrice(BigDecimal price) {
    if (price == null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "preço é obrigatório");
    }
    if (price.signum() < 0) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "preço não pode ser negativo");
    }
    return price.setScale(2, RoundingMode.HALF_UP);
  }

  /** O motivo é o rastro humano da alteração; sem ele o evento de auditoria não se sustenta. */
  private static String requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "motivo da alteração é obrigatório");
    }
    return reason;
  }

  /** O antes/depois mínimo do §7.2: só o preço, nunca o produto inteiro. */
  private static Map<String, Object> beforeAndAfter(BigDecimal before, BigDecimal after) {
    return Map.of("before", Map.of("price", before), "after", Map.of("price", after));
  }

  private static NotFoundException notFound(UUID id) {
    return new NotFoundException(
        ErrorCode.PRODUCT_NOT_FOUND, "produto %s não encontrado".formatted(id));
  }
}
