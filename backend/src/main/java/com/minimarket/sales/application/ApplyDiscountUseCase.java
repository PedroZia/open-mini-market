package com.minimarket.sales.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.auth.application.AuthorizationService;
import com.minimarket.sales.domain.DiscountType;
import com.minimarket.sales.domain.Sale;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.Permission;
import com.minimarket.shared.domain.Store;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Aplica o desconto na venda aberta (passo 810, BR-04): exige a permissão {@code
 * sale.discount.apply}, motivo preenchido e percentual efetivo dentro do limite da loja — quem
 * calcula o desconto e o total é o agregado (BR-03/BR-12), nunca o cliente. Uma execução = uma
 * transação (§2.2, regra 6): coluna de desconto, totais da venda e evento saem juntos ou não saem.
 *
 * <p>A ordem das checagens é deliberada: a permissão vem primeiro (deny by default, passo 305) —
 * sem ela o operador recebe 403 {@code ACCESS_DENIED} sem que a venda seja lida e sem descobrir se
 * ela existe. Depois a {@link SaleAccessGuard} (BR-11, §9.4): venda inexistente é 404 {@code
 * SALE_NOT_FOUND}, venda de outro caixa é 403 {@code ACCESS_DENIED} e venda fora de {@code OPEN} é
 * 409 {@code SALE_NOT_OPEN}, conferido antes de mutar o agregado. Só então a forma do comando
 * (tipo, valor e motivo ausentes ou em branco são 400 {@code VALIDATION_ERROR}) e, por fim, o
 * limite da loja — a recusa do domínio para tipo/valor inválidos (422) fica como backstop, como nas
 * demais operações de venda.
 *
 * <p>O limite é o {@code max_discount_percent} da loja configurada ({@code
 * minimarket.store.default-code}, padrão do módulo): o percentual efetivo do desconto é o próprio
 * valor em {@code PERCENT} e o quanto o valor representa do subtotal em {@code VALUE} — {@code
 * value × 100 ÷ subtotal}, escala 2 com {@code HALF_UP}; venda sem subtotal com desconto por valor
 * conta como 100%, porque qualquer valor sobre zero é desconto integral. Acima do limite é 422
 * {@code DISCOUNT_LIMIT_EXCEEDED}; exatamente no limite passa. A permissão de quem aplica e o
 * limite da loja são independentes: sem permissão é 403, com permissão e acima do limite é 422.
 *
 * <p>A leitura é por {@code findById}, não por {@code lockById}: a linha fica no contexto de
 * persistência e o {@code update} do 803 confere a versão no flush — quem alterou a mesma venda
 * entre a leitura e a gravação recebe 409 {@code CONCURRENT_MODIFICATION}, o caminho que o 814
 * exercita. Aplicar de novo substitui o desconto anterior (o agregado guarda um desconto por
 * venda).
 *
 * <p>Auditoria (§7.2): {@code SALE_DISCOUNT_APPLIED} na mesma transação, com o motivo do desconto
 * em {@code reason} e tipo, valor, valor calculado e total em {@code details} — o ator que
 * autorizou vem do {@code OperationContext}, não do comando. Devolve o agregado atualizado — quem
 * monta a resposta é a API (passo 811).
 */
@ApplicationScoped
public class ApplyDiscountUseCase {

  /** Ação do desconto aplicado (§7.2). */
  private static final String SALE_DISCOUNT_APPLIED_ACTION = "SALE_DISCOUNT_APPLIED";

  /** Alvo do evento: a venda que recebeu o desconto. */
  private static final String SALE_ENTITY_TYPE = "SALE";

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /** Escala do percentual efetivo no teste do limite, a mesma de {@code max_discount_percent}. */
  private static final int PERCENT_SCALE = 2;

  /** Guarda de posse e estado da venda (BR-11): a operação só segue na venda do caixa da sessão. */
  @Inject SaleAccessGuard saleAccessGuard;

  /** Porta da venda: o {@code update} confere a versão lida — o lock é o otimista, como no 808. */
  @Inject SaleStore saleStore;

  /** Porta da loja: o limite de desconto é parâmetro da loja, não do comando (§5.4). */
  @Inject StoreLookup storeLookup;

  /** Auditoria do desconto (§7.2), na transação da venda. */
  @Inject AuditRecorder auditRecorder;

  /**
   * Permissões efetivas de quem pede (passo 305): aplicar desconto exige {@code
   * sale.discount.apply}.
   */
  @Inject AuthorizationService authorizationService;

  /** Loja única do MVP: o limite de desconto vem da loja configurada, como no 805. */
  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  /**
   * 403 {@code ACCESS_DENIED} para sessão sem {@code sale.discount.apply}; 404 {@code
   * SALE_NOT_FOUND} para venda inexistente; 403 {@code ACCESS_DENIED} para venda de outro caixa;
   * 409 {@code SALE_NOT_OPEN} para venda concluída; 400 {@code VALIDATION_ERROR} para tipo, valor
   * ou motivo ausentes/em branco; 422 {@code DISCOUNT_LIMIT_EXCEEDED} acima do limite da loja.
   * Devolve o agregado com o desconto e os totais recalculados.
   */
  @Transactional
  public Sale execute(ApplyDiscountCommand command) {
    authorizationService.require(Permission.SALE_DISCOUNT_APPLY);
    Sale sale =
        SaleAccessGuard.requireOpen(
            saleAccessGuard.requireOwned(command.saleId(), command.cashRegisterId()));
    DiscountType type = requireType(command.type());
    BigDecimal value = requireValue(command.value());
    String reason = requireReason(command.reason());
    requireWithinStoreLimit(type, value, sale.subtotal());
    sale.applyDiscount(type, value, reason);
    saleStore.update(sale);
    auditRecorder.record(
        SALE_DISCOUNT_APPLIED_ACTION,
        SALE_ENTITY_TYPE,
        sale.id(),
        sale.discountReason(),
        details(sale));

    return sale;
  }

  /** Desconto sem tipo não é aplicável; o domínio recusaria com 422 e a forma é 400. */
  private static DiscountType requireType(DiscountType type) {
    if (type == null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "tipo do desconto é obrigatório");
    }
    return type;
  }

  /** Desconto sem valor positivo não existe (BR-03): nulo ou não positivo é 400. */
  private static BigDecimal requireValue(BigDecimal value) {
    if (value == null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "valor do desconto é obrigatório");
    }
    if (value.signum() <= 0) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "valor do desconto deve ser maior que zero");
    }
    return value;
  }

  /** BR-04: desconto sem motivo não é operação auditável — nulo ou em branco é 400. */
  private static String requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "motivo do desconto é obrigatório");
    }
    return reason;
  }

  /** Acima do limite da loja é 422 {@code DISCOUNT_LIMIT_EXCEEDED}; no limite exato passa. */
  private void requireWithinStoreLimit(DiscountType type, BigDecimal value, BigDecimal subtotal) {
    BigDecimal maxPercent = currentStore().maxDiscountPercent();
    BigDecimal effectivePercent = effectivePercent(type, value, subtotal);
    if (effectivePercent.compareTo(maxPercent) > 0) {
      throw new BusinessException(
          ErrorCode.DISCOUNT_LIMIT_EXCEEDED,
          "desconto de %s%% excede o limite de %s%% da loja"
              .formatted(effectivePercent.toPlainString(), maxPercent.toPlainString()));
    }
  }

  /**
   * Percentual efetivo do desconto para o limite da loja: em {@code PERCENT} é o próprio valor; em
   * {@code VALUE} é o quanto ele representa do subtotal, em escala 2 com {@code HALF_UP}. Venda sem
   * subtotal e desconto por valor positivo conta como 100% — qualquer valor sobre zero é desconto
   * integral.
   */
  private static BigDecimal effectivePercent(
      DiscountType type, BigDecimal value, BigDecimal subtotal) {
    if (type == DiscountType.PERCENT) {
      return value;
    }
    if (subtotal.signum() == 0) {
      return HUNDRED;
    }
    return value.multiply(HUNDRED).divide(subtotal, PERCENT_SCALE, RoundingMode.HALF_UP);
  }

  /** Limite da loja atual, pela configuração — o MVP tem loja única e o comando não a escolhe. */
  private Store currentStore() {
    return storeLookup
        .findByCode(defaultStoreCode)
        .orElseThrow(
            () -> new IllegalStateException("loja configurada não existe: " + defaultStoreCode));
  }

  /**
   * Details do evento: o desconto como o agregado o guardou (tipo e valor informado), o valor
   * calculado pelo servidor e o total resultante. O mapa é ordenado e mutável porque acompanha o
   * formato dos demais eventos de venda.
   */
  private static Map<String, Object> details(Sale sale) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("type", sale.discountType().name());
    details.put("value", sale.discountValue());
    details.put("discountAmount", sale.discountAmount());
    details.put("total", sale.total());
    return details;
  }
}
