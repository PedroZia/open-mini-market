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
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Entrada de mercadoria com custo (passo 706): repõe o saldo como movimento {@code PURCHASE_IN} do
 * ledger e, quando o custo unitário vem informado, atualiza o {@code cost_price} do produto. Uma
 * execução = uma transação (§2.2, regra 6): o movimento, a atualização do custo e o evento de
 * auditoria saem juntos ou não saem; falha ao auditar derruba a entrada.
 *
 * <p>Quem move o saldo é o {@link StockService} (BR-08): este caso de uso não toca {@code
 * ProductStockStore} nem {@code StockMovementStore} — o serviço aplica o delta sob o lock da linha
 * de saldo, grava o {@code balance_after} e o custo no movimento. A quantidade é sempre positiva
 * (entrada soma) e entra na escala 3 do projeto com {@code HALF_UP} (§4.4); o custo é opcional —
 * nulo não mexe no cadastro — e, quando informado, entra na escala 2 do dinheiro com {@code
 * HALF_UP}.
 *
 * <p>Validação na ordem: quantidade ausente ou não positiva e custo negativo são 400 {@code
 * VALIDATION_ERROR} antes de qualquer consulta. O produto vem da porta {@code ProductStore} do
 * catálogo ({@code inventory → catalog} de {@code application} para {@code application}, como no
 * passo 705): id desconhecido ou soft-deletado é 404 {@code PRODUCT_NOT_FOUND}. O motivo é opcional
 * — o recebimento é rotina, não exceção — e vai como veio para o movimento e o evento.
 *
 * <p>Auditoria (§7.2): {@code STOCK_RECEIVED} na mesma transação, com o produto em {@code
 * entityId}, o motivo em {@code reason} e o antes/depois mínimo do saldo em {@code details} (chaves
 * {@code before}/{@code after}, como no {@code STOCK_ADJUSTED} do passo 705) mais a quantidade e o
 * custo que a entrada trouxe. O ator vem do comando — quem o conhece é a API (passo 706), não este
 * caso de uso.
 */
@ApplicationScoped
public class RegisterStockReceiptUseCase {

  /** Ação da entrada de mercadoria (passo 706, no padrão do {@code STOCK_ADJUSTED} do §7.2). */
  private static final String STOCK_RECEIVED_ACTION = "STOCK_RECEIVED";

  /** Alvo do evento: o produto cujo saldo subiu. */
  private static final String PRODUCT_ENTITY_TYPE = "PRODUCT";

  /** Escala da quantidade (§4.4). */
  private static final int QUANTITY_SCALE = 3;

  /** Escala do dinheiro (§4.4). */
  private static final int MONEY_SCALE = 2;

  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  @Inject ProductStore productStore;

  /** A única porta de alteração de saldo (BR-08). */
  @Inject StockService stockService;

  /** Auditoria da entrada (passo 706), na transação do movimento. */
  @Inject AuditRecorder auditRecorder;

  /**
   * 400 {@code VALIDATION_ERROR} para quantidade ausente/não positiva ou custo negativo; 404 {@code
   * PRODUCT_NOT_FOUND} para produto inexistente ou soft-deletado. Devolve o movimento gravado com o
   * saldo antes/depois calculado sob o lock e os valores normalizados.
   */
  @Transactional
  public AppliedStockReceipt execute(RegisterStockReceiptCommand command) {
    BigDecimal quantity = requireQuantity(command.quantity());
    BigDecimal unitCost = requireValidUnitCost(command.unitCost());
    requireLiveProduct(command.productId());

    AppliedStockMovement movement =
        stockService.applyMovement(
            new ApplyStockMovementCommand(
                command.productId(),
                StockMovementType.PURCHASE_IN,
                quantity,
                unitCost,
                null,
                null,
                command.reason(),
                command.performedByUserId()));
    if (unitCost != null) {
      productStore.updateCostPrice(command.productId(), unitCost);
    }
    auditRecorder.record(
        STOCK_RECEIVED_ACTION,
        PRODUCT_ENTITY_TYPE,
        command.productId(),
        command.reason(),
        details(quantity, unitCost, movement.balanceBefore(), movement.balanceAfter()));
    return new AppliedStockReceipt(
        movement.movementId(),
        command.productId(),
        quantity,
        unitCost,
        movement.balanceBefore(),
        movement.balanceAfter());
  }

  /** Entrada sem quantidade não é entrada: ausente ou não positiva é 400. */
  private static BigDecimal requireQuantity(BigDecimal quantity) {
    if (quantity == null) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "quantidade da entrada é obrigatória");
    }
    if (quantity.signum() <= 0) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "quantidade da entrada deve ser maior que zero");
    }
    return quantity.setScale(QUANTITY_SCALE, ROUNDING);
  }

  /** Custo é opcional; quando vem, é dinheiro do projeto e não pode ser negativo (§4.4). */
  private static BigDecimal requireValidUnitCost(BigDecimal unitCost) {
    if (unitCost == null) {
      return null;
    }
    if (unitCost.signum() < 0) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "custo unitário não pode ser negativo");
    }
    return unitCost.setScale(MONEY_SCALE, ROUNDING);
  }

  /** Produto vivo: id desconhecido ou soft-deletado conta como inexistente, como no passo 705. */
  private void requireLiveProduct(UUID productId) {
    productStore
        .findById(productId)
        .filter(product -> product.deletedAt() == null)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCode.PRODUCT_NOT_FOUND, "produto %s não encontrado".formatted(productId)));
  }

  /**
   * O antes/depois mínimo do §7.2 mais o que a entrada trouxe: o saldo de cada lado, a quantidade
   * recebida e o custo. {@code Map.of} não aceita valor nulo e o custo é opcional — daí o mapa
   * mutável.
   */
  private static Map<String, Object> details(
      BigDecimal quantity, BigDecimal unitCost, BigDecimal before, BigDecimal after) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("before", Map.of("quantity", before));
    details.put("after", Map.of("quantity", after));
    details.put("quantity", quantity);
    details.put("unitCost", unitCost);
    return details;
  }
}
