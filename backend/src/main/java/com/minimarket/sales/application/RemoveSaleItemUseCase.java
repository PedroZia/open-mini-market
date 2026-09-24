package com.minimarket.sales.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tira um item da venda aberta (passo 809): o item é identificado pelo produto (decisão do 802) e
 * quem recalcula subtotal, total e itemCount é o agregado (BR-02). Uma execução = uma transação
 * (§2.2, regra 6): a linha removida de {@code sale_items}, os totais da venda e o evento saem
 * juntos ou não saem.
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
 * exercita. O {@code update} apaga a linha do item removido e renumera as posições (o {@code
 * line_number} acompanha a lista do agregado).
 *
 * <p>Auditoria (§7.2): {@code SALE_ITEM_REMOVED} na mesma transação, com a venda em {@code
 * entityId} e produto, quantidade e total da linha removidos mais os totais/itemCount resultantes
 * em {@code details}. Devolve o agregado atualizado — quem monta a resposta é a API (passo 809b).
 */
@ApplicationScoped
public class RemoveSaleItemUseCase {

  /** Ação da remoção de item (§7.2). */
  private static final String SALE_ITEM_REMOVED_ACTION = "SALE_ITEM_REMOVED";

  /** Alvo do evento: a venda cujo item saiu. */
  private static final String SALE_ENTITY_TYPE = "SALE";

  /** Guarda de posse e estado da venda (BR-11): a operação só segue na venda do caixa da sessão. */
  @Inject SaleAccessGuard saleAccessGuard;

  /** Porta da venda: o {@code update} confere a versão lida — o lock é o otimista, como no 808. */
  @Inject SaleStore saleStore;

  /** Auditoria da remoção (§7.2), na transação do item. */
  @Inject AuditRecorder auditRecorder;

  /**
   * 404 {@code SALE_NOT_FOUND} para venda inexistente; 403 {@code ACCESS_DENIED} para venda de
   * outro caixa; 409 {@code SALE_NOT_OPEN} para venda concluída; 404 {@code SALE_ITEM_NOT_FOUND}
   * para produto que não está na venda. Devolve o agregado sem o item e com os totais recalculados.
   */
  @Transactional
  public Sale execute(RemoveSaleItemCommand command) {
    Sale sale =
        SaleAccessGuard.requireOpen(
            saleAccessGuard.requireOwned(command.saleId(), command.cashRegisterId()));
    SaleItem removed = SaleAccessGuard.requireItem(sale, command.productId());
    sale.removeItem(command.productId());
    saleStore.update(sale);
    auditRecorder.record(
        SALE_ITEM_REMOVED_ACTION, SALE_ENTITY_TYPE, sale.id(), null, details(removed, sale));

    return sale;
  }

  /**
   * Details do evento: o item como ele saiu (produto, quantidade e total da linha) e o que a
   * remoção deixou na venda — totais e itemCount recalculados pelo agregado. O mapa é ordenado e
   * mutável para acompanhar o formato dos demais eventos de item.
   */
  private static Map<String, Object> details(SaleItem removed, Sale sale) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("productId", removed.productId());
    details.put("quantity", removed.quantity());
    details.put("lineTotal", removed.lineTotal());
    details.put("subtotal", sale.subtotal());
    details.put("discountAmount", sale.discountAmount());
    details.put("total", sale.total());
    details.put("itemCount", sale.itemCount());
    return details;
  }
}
