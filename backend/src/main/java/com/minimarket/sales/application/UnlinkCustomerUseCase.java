package com.minimarket.sales.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.sales.domain.Sale;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Map;
import java.util.UUID;

/**
 * Desvincula o cliente da venda aberta (passo 811): a venda volta a ser anônima e o CPF sai da
 * nota. Uma execução = uma transação (§2.2, regra 6): a coluna {@code customer_id} da venda e o
 * evento saem juntos ou não saem.
 *
 * <p>A venda vem pela {@link SaleAccessGuard} (BR-11, §9.4): venda inexistente é 404 {@code
 * SALE_NOT_FOUND} para qualquer sessão, venda de outro caixa é 403 {@code ACCESS_DENIED} e venda
 * fora de {@code OPEN} é 409 {@code SALE_NOT_OPEN}, conferido antes de mutar o agregado.
 *
 * <p>Venda sem cliente vinculado é no-op: devolve o agregado intacto, <em>sem</em> {@code update} e
 * sem evento — não houve operação para gravar, e o {@code DELETE} da API (passo 811b) fica
 * idempotente (desvincular duas vezes é o mesmo que desvincular uma). É a mesma escolha da remoção
 * de desconto do 810: o no-op não inventa rastro.
 *
 * <p>A leitura é por {@code findById}, não por {@code lockById}: a linha fica no contexto de
 * persistência e o {@code update} do 803 confere a versão no flush — quem alterou a mesma venda
 * entre a leitura e a gravação recebe 409 {@code CONCURRENT_MODIFICATION}, o caminho que o 814
 * exercita.
 *
 * <p>Auditoria (§7.2): {@code SALE_CUSTOMER_UNLINKED} na mesma transação, com a venda em {@code
 * entityId} e o cliente que saiu em {@code details} — só o id, porque o cliente nunca é apagado e o
 * rastro aponta para a linha dele (o nome é do evento de vínculo, quando ele já estava em mãos). A
 * ação não está na lista de exemplos do §7.2 — o catálogo é aberto e o verbo segue o padrão de
 * {@code SALE_ITEM_REMOVED} (decisão registrada no commit do passo 811). Devolve o agregado
 * atualizado — quem monta a resposta é a API (passo 811b).
 */
@ApplicationScoped
public class UnlinkCustomerUseCase {

  /**
   * Ação do desvínculo: o §7.2 lista exemplos, não o catálogo fechado; o verbo segue o padrão de
   * {@code SALE_ITEM_REMOVED}.
   */
  private static final String SALE_CUSTOMER_UNLINKED_ACTION = "SALE_CUSTOMER_UNLINKED";

  /** Alvo do evento: a venda que perdeu o cliente. */
  private static final String SALE_ENTITY_TYPE = "SALE";

  /** Guarda de posse e estado da venda (BR-11): a operação só segue na venda do caixa da sessão. */
  @Inject SaleAccessGuard saleAccessGuard;

  /** Porta da venda: o {@code update} confere a versão lida — o lock é o otimista, como no 808. */
  @Inject SaleStore saleStore;

  /** Auditoria do desvínculo (§7.2), na transação da venda. */
  @Inject AuditRecorder auditRecorder;

  /**
   * 404 {@code SALE_NOT_FOUND} para venda inexistente; 403 {@code ACCESS_DENIED} para venda de
   * outro caixa; 409 {@code SALE_NOT_OPEN} para venda concluída. Venda sem cliente vinculado
   * devolve o agregado intacto, sem gravar nem auditar.
   */
  @Transactional
  public Sale execute(UnlinkCustomerCommand command) {
    Sale sale =
        SaleAccessGuard.requireOpen(
            saleAccessGuard.requireOwned(command.saleId(), command.cashRegisterId()));
    UUID customerId = sale.customerId();
    if (customerId == null) {
      // Sem vínculo não há o que tirar: o no-op devolve a venda e não deixa rastro.
      return sale;
    }
    sale.unlinkCustomer();
    saleStore.update(sale);
    auditRecorder.record(
        SALE_CUSTOMER_UNLINKED_ACTION, SALE_ENTITY_TYPE, sale.id(), null, details(customerId));

    return sale;
  }

  /**
   * Details do evento: o cliente que saiu, pelo id — a linha de {@code customers} nunca é apagada
   * (soft delete), então o id é o rastro suficiente.
   */
  private static Map<String, Object> details(UUID customerId) {
    return Map.of("customerId", customerId);
  }
}
