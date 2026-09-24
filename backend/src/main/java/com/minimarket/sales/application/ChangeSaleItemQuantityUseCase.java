package com.minimarket.sales.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Troca a quantidade de um item da venda aberta (passo 809): a quantidade nova chega absoluta, o
 * item é identificado pelo produto (decisão do 802) e quem recalcula o total da linha e os totais
 * da venda é o agregado (BR-02, BR-12) — nada de valor calculado vindo do cliente. Uma execução =
 * uma transação (§2.2, regra 6): linha do item, totais da venda e evento saem juntos ou não saem.
 *
 * <p>A venda vem pela {@link SaleAccessGuard} (BR-11, §9.4): venda inexistente é 404 {@code
 * SALE_NOT_FOUND}, venda de outro caixa é 403 {@code ACCESS_DENIED} e venda fora de {@code OPEN} é
 * 409 {@code SALE_NOT_OPEN}, conferido antes de mutar. Produto que não está na venda é 404 {@code
 * SALE_ITEM_NOT_FOUND}, checado antes de chamar o agregado — a recusa do domínio para o item
 * ausente (422) fica como backstop.
 *
 * <p>A leitura é por {@code findById}, não por {@code lockById}: a linha fica no contexto de
 * persistência e o {@code update} do 803 confere a versão no flush — quem alterou a mesma venda
 * entre a leitura e a gravação recebe 409 {@code CONCURRENT_MODIFICATION}, o caminho que o 814
 * exercita.
 *
 * <p>Auditoria (§7.2): {@code SALE_ITEM_QUANTITY_CHANGED} na mesma transação, com a venda em {@code
 * entityId} e produto, quantidade antes/depois e os totais/itemCount resultantes em {@code
 * details}. Devolve o agregado atualizado — quem monta a resposta é a API (passo 809b).
 */
@ApplicationScoped
public class ChangeSaleItemQuantityUseCase {

  /** Ação da troca de quantidade do item (§7.2). */
  private static final String SALE_ITEM_QUANTITY_CHANGED_ACTION = "SALE_ITEM_QUANTITY_CHANGED";

  /** Alvo do evento: a venda cujo item mudou. */
  private static final String SALE_ENTITY_TYPE = "SALE";

  /** Guarda de posse e estado da venda (BR-11): a operação só segue na venda do caixa da sessão. */
  @Inject SaleAccessGuard saleAccessGuard;

  /** Porta da venda: o {@code update} confere a versão lida — o lock é o otimista, como no 808. */
  @Inject SaleStore saleStore;

  /** Auditoria da mudança (§7.2), na transação do item. */
  @Inject AuditRecorder auditRecorder;

  /**
   * 404 {@code SALE_NOT_FOUND} para venda inexistente; 403 {@code ACCESS_DENIED} para venda de
   * outro caixa; 409 {@code SALE_NOT_OPEN} para venda concluída; 404 {@code SALE_ITEM_NOT_FOUND}
   * para produto que não está na venda. Devolve o agregado com a quantidade trocada e os totais
   * recalculados.
   */
  @Transactional
  public Sale execute(ChangeSaleItemQuantityCommand command) {
    Sale sale =
        SaleAccessGuard.requireOpen(
            saleAccessGuard.requireOwned(command.saleId(), command.cashRegisterId()));
    BigDecimal previousQuantity = SaleAccessGuard.requireItem(sale, command.productId()).quantity();
    sale.changeQuantity(command.productId(), command.quantity());
    saleStore.update(sale);
    auditRecorder.record(
        SALE_ITEM_QUANTITY_CHANGED_ACTION,
        SALE_ENTITY_TYPE,
        sale.id(),
        null,
        details(command.productId(), previousQuantity, sale),
        sale.cashSessionId());

    return sale;
  }

  /**
   * Details do evento: o produto, a quantidade antes/depois e o que a troca deixou na venda — o
   * total da linha acompanha a quantidade e os totais são os recalculados pelo agregado. O mapa é
   * ordenado e mutável porque os campos acompanham o formato dos demais eventos de item.
   */
  private static Map<String, Object> details(
      UUID productId, BigDecimal previousQuantity, Sale sale) {
    SaleItem changed = SaleAccessGuard.requireItem(sale, productId);
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("productId", productId);
    details.put("previousQuantity", previousQuantity);
    details.put("quantity", changed.quantity());
    details.put("lineTotal", changed.lineTotal());
    details.put("subtotal", sale.subtotal());
    details.put("discountAmount", sale.discountAmount());
    details.put("total", sale.total());
    details.put("itemCount", sale.itemCount());
    return details;
  }
}
