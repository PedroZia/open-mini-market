package com.minimarket.sales.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.auth.application.AuthorizationService;
import com.minimarket.sales.domain.DiscountType;
import com.minimarket.sales.domain.Sale;
import com.minimarket.shared.domain.Permission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tira o desconto da venda aberta (passo 810): o agregado zera tipo, valor e motivo e recalcula o
 * total, que volta ao subtotal (BR-02/BR-03). Uma execução = uma transação (§2.2, regra 6): coluna
 * de desconto, totais da venda e evento saem juntos ou não saem.
 *
 * <p>Quem remove precisa da mesma permissão de quem aplica, {@code sale.discount.apply} (BR-04):
 * desfazer um desconto é tão sensível quanto concedê-lo. A permissão vem primeiro (deny by default,
 * passo 305) — sem ela é 403 {@code ACCESS_DENIED} sem que a venda seja lida. Depois a {@link
 * SaleAccessGuard} (BR-11, §9.4): venda inexistente é 404 {@code SALE_NOT_FOUND}, venda de outro
 * caixa é 403 {@code ACCESS_DENIED} e venda fora de {@code OPEN} é 409 {@code SALE_NOT_OPEN},
 * conferido antes de mutar o agregado.
 *
 * <p>Venda sem desconto é no-op: devolve o agregado intacto, <em>sem</em> {@code update} e sem
 * evento — não houve operação para gravar, e o {@code DELETE} da API (passo 811) fica idempotente
 * (remover duas vezes é o mesmo que remover uma). É a mesma escolha do logout/revogação de sessão
 * (passo 208/210): o no-op não inventa rastro.
 *
 * <p>A leitura é por {@code findById}, não por {@code lockById}: a linha fica no contexto de
 * persistência e o {@code update} do 803 confere a versão no flush — quem alterou a mesma venda
 * entre a leitura e a gravação recebe 409 {@code CONCURRENT_MODIFICATION}, o caminho que o 814
 * exercita.
 *
 * <p>Auditoria (§7.2): {@code SALE_DISCOUNT_REMOVED} na mesma transação, com o motivo do desconto
 * que saiu em {@code reason} e tipo, valor, valor calculado e total resultante em {@code details}.
 * A ação não está na lista de exemplos do §7.2 — o catálogo é aberto e o verbo segue o mesmo padrão
 * de {@code SALE_ITEM_REMOVED} (decisão registrada no commit do passo 810). Devolve o agregado
 * atualizado — quem monta a resposta é a API (passo 811).
 */
@ApplicationScoped
public class RemoveDiscountUseCase {

  /**
   * Ação da remoção do desconto: o §7.2 lista exemplos, não o catálogo fechado; o verbo segue o
   * padrão de {@code SALE_ITEM_REMOVED}.
   */
  private static final String SALE_DISCOUNT_REMOVED_ACTION = "SALE_DISCOUNT_REMOVED";

  /** Alvo do evento: a venda que perdeu o desconto. */
  private static final String SALE_ENTITY_TYPE = "SALE";

  /** Guarda de posse e estado da venda (BR-11): a operação só segue na venda do caixa da sessão. */
  @Inject SaleAccessGuard saleAccessGuard;

  /** Porta da venda: o {@code update} confere a versão lida — o lock é o otimista, como no 808. */
  @Inject SaleStore saleStore;

  /** Auditoria da remoção (§7.2), na transação da venda. */
  @Inject AuditRecorder auditRecorder;

  /**
   * Permissões efetivas de quem pede (passo 305): remover desconto exige a mesma {@code
   * sale.discount.apply} de aplicá-lo.
   */
  @Inject AuthorizationService authorizationService;

  /**
   * 403 {@code ACCESS_DENIED} para sessão sem {@code sale.discount.apply}; 404 {@code
   * SALE_NOT_FOUND} para venda inexistente; 403 {@code ACCESS_DENIED} para venda de outro caixa;
   * 409 {@code SALE_NOT_OPEN} para venda concluída. Venda sem desconto devolve o agregado intacto,
   * sem gravar nem auditar.
   */
  @Transactional
  public Sale execute(RemoveDiscountCommand command) {
    authorizationService.require(Permission.SALE_DISCOUNT_APPLY);
    Sale sale =
        SaleAccessGuard.requireOpen(
            saleAccessGuard.requireOwned(command.saleId(), command.cashRegisterId()));
    if (sale.discountType() == null) {
      // Sem desconto não há o que remover: o no-op devolve a venda e não deixa rastro.
      return sale;
    }
    DiscountType removedType = sale.discountType();
    BigDecimal removedValue = sale.discountValue();
    BigDecimal removedAmount = sale.discountAmount();
    String removedReason = sale.discountReason();
    sale.removeDiscount();
    saleStore.update(sale);
    auditRecorder.record(
        SALE_DISCOUNT_REMOVED_ACTION,
        SALE_ENTITY_TYPE,
        sale.id(),
        removedReason,
        details(removedType, removedValue, removedAmount, sale));

    return sale;
  }

  /**
   * Details do evento: o desconto como ele saiu (tipo, valor informado e valor calculado) e o total
   * resultante, que voltou ao subtotal. O mapa é ordenado e mutável porque acompanha o formato dos
   * demais eventos de venda.
   */
  private static Map<String, Object> details(
      DiscountType type, BigDecimal value, BigDecimal amount, Sale sale) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("type", type.name());
    details.put("value", value);
    details.put("discountAmount", amount);
    details.put("total", sale.total());
    return details;
  }
}
