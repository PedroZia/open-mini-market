package com.minimarket.inventory.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.inventory.domain.StockMovementType;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Ajuste manual de estoque com rastro (passo 705, BR-13): corrige a divergência entre o saldo do
 * sistema e o contado sem apagar histórico — o ajuste entra como movimento {@code ADJUSTMENT} do
 * ledger, que é append-only. Uma execução = uma transação (§2.2, regra 6): o movimento e o evento
 * de auditoria saem juntos ou não saem; falha ao auditar derruba o ajuste.
 *
 * <p>Quem move o saldo é o {@link StockService} (BR-08): este caso de uso não toca {@code
 * ProductStockStore} nem {@code StockMovementStore} — o serviço aplica o delta sob o lock da linha
 * de saldo, grava o {@code balance_after} e recusa com 422 {@code INSUFFICIENT_STOCK} quando a loja
 * tem {@code allow_negative_stock=false} e o saldo ficaria negativo (BR-09). O delta é assinado: o
 * cliente manda positivo para aumentar e negativo para reduzir.
 *
 * <p>Validação na ordem: delta ausente ou zero e motivo em branco são 400 {@code VALIDATION_ERROR}
 * antes de qualquer consulta — um ajuste sem mudança não tem efeito nem rastro útil. O produto vem
 * da porta {@code ProductStore} do catálogo ({@code inventory → catalog} de {@code application}
 * para {@code application}, como no passo 704): id desconhecido ou soft-deletado é 404 {@code
 * PRODUCT_NOT_FOUND}.
 *
 * <p>Auditoria (§7.2): {@code STOCK_ADJUSTED} na mesma transação, com o produto em {@code
 * entityId}, o motivo em {@code reason} e o antes/depois mínimo do saldo em {@code details} (chaves
 * {@code before}/{@code after}, como no preço do passo 411). O ator vem do comando — quem o conhece
 * é a API (passo 705), não este caso de uso.
 */
@ApplicationScoped
public class AdjustStockUseCase {

  /** Ação do ajuste manual (§7.2). */
  private static final String STOCK_ADJUSTED_ACTION = "STOCK_ADJUSTED";

  /** Alvo do evento: o produto cujo saldo foi ajustado. */
  private static final String PRODUCT_ENTITY_TYPE = "PRODUCT";

  @Inject ProductStore productStore;

  /** A única porta de alteração de saldo (BR-08). */
  @Inject StockService stockService;

  /** Auditoria do ajuste (passo 705), na transação do movimento. */
  @Inject AuditRecorder auditRecorder;

  /**
   * 400 {@code VALIDATION_ERROR} para delta ausente/zero ou motivo em branco; 404 {@code
   * PRODUCT_NOT_FOUND} para produto inexistente ou soft-deletado; 422 {@code INSUFFICIENT_STOCK} do
   * {@link StockService} quando o saldo ficaria negativo com a loja sem estoque negativo. Devolve o
   * movimento gravado com o saldo antes/depois calculado sob o lock.
   */
  @Transactional
  public AppliedStockAdjustment execute(AdjustStockCommand command) {
    BigDecimal quantityDelta = requireDelta(command.quantityDelta());
    String reason = requireReason(command.reason());
    requireLiveProduct(command.productId());

    AppliedStockMovement movement =
        stockService.applyMovement(
            new ApplyStockMovementCommand(
                command.productId(),
                StockMovementType.ADJUSTMENT,
                quantityDelta,
                null,
                null,
                null,
                reason,
                command.performedByUserId()));
    auditRecorder.record(
        STOCK_ADJUSTED_ACTION,
        PRODUCT_ENTITY_TYPE,
        command.productId(),
        reason,
        beforeAndAfter(movement.balanceBefore(), movement.balanceAfter()));
    return new AppliedStockAdjustment(
        movement.movementId(),
        command.productId(),
        quantityDelta,
        movement.balanceBefore(),
        movement.balanceAfter());
  }

  /** Ajuste sem mudança não tem efeito nem rastro útil: zero e ausente são 400. */
  private static BigDecimal requireDelta(BigDecimal quantityDelta) {
    if (quantityDelta == null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "quantidade do ajuste é obrigatória");
    }
    if (quantityDelta.signum() == 0) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "quantidade do ajuste não pode ser zero");
    }
    return quantityDelta;
  }

  /** BR-13: ajuste sem motivo não é operação auditável. */
  private static String requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "motivo do ajuste é obrigatório");
    }
    return reason;
  }

  /** Produto vivo: id desconhecido ou soft-deletado conta como inexistente, como no passo 704. */
  private void requireLiveProduct(UUID productId) {
    productStore
        .findById(productId)
        .filter(product -> product.deletedAt() == null)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCode.PRODUCT_NOT_FOUND, "produto %s não encontrado".formatted(productId)));
  }

  /** O antes/depois mínimo do §7.2: só o saldo, nunca o produto inteiro. */
  private static Map<String, Object> beforeAndAfter(BigDecimal before, BigDecimal after) {
    return Map.of("before", Map.of("quantity", before), "after", Map.of("quantity", after));
  }
}
