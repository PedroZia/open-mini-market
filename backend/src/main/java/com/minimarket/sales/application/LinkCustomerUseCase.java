package com.minimarket.sales.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.customers.application.CustomerStore;
import com.minimarket.customers.application.CustomerSummary;
import com.minimarket.sales.domain.Sale;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Map;
import java.util.UUID;

/**
 * Vincula o cliente à venda aberta (passo 811): o CPF na nota e a identificação do consumidor saem
 * daqui. Uma execução = uma transação (§2.2, regra 6): a coluna {@code customer_id} da venda e o
 * evento saem juntos ou não saem.
 *
 * <p>O cliente precisa estar <em>ativo</em>: inativo é 422 {@code CUSTOMER_INACTIVE} — a linha
 * existe e o operador precisa saber que ela não vende, não que ela sumiu, como no {@code
 * PRODUCT_INACTIVE} do 808. Para distinguir isso de "nunca existiu" (404 {@code
 * CUSTOMER_NOT_FOUND}) a leitura é por {@link CustomerStore#findAnyById}, que enxerga o desativado
 * — o {@code findById} do 502 só enxerga vivo e devolveria 404 para os dois casos.
 *
 * <p>A venda vem pela {@link SaleAccessGuard} (BR-11, §9.4): venda inexistente é 404 {@code
 * SALE_NOT_FOUND} para qualquer sessão, venda de outro caixa é 403 {@code ACCESS_DENIED} e venda
 * fora de {@code OPEN} é 409 {@code SALE_NOT_OPEN}, conferido antes de mutar o agregado — a recusa
 * do domínio (422) fica como backstop. A checagem da venda precede a do cliente: quem não é dono
 * não descobre nem se o cliente existe.
 *
 * <p>A permissão {@code sale.create} de quem vincula é do porteiro da rota (passo 811b): vincular
 * cliente é operar a venda, como incluir item — o caso de uso do 810 checa a permissão de desconto
 * porque ela é específica ({@code sale.discount.apply}).
 *
 * <p>A leitura é por {@code findById}, não por {@code lockById}: a linha fica no contexto de
 * persistência e o {@code update} do 803 confere a versão no flush — quem alterou a mesma venda
 * entre a leitura e a gravação recebe 409 {@code CONCURRENT_MODIFICATION}, o caminho que o 814
 * exercita. Vincular o mesmo cliente de novo é operação como qualquer outra (grava e audita); o que
 * não deixa rastro é o desvincular sem vínculo (passo do {@link UnlinkCustomerUseCase}).
 *
 * <p>Auditoria (§7.2): {@code SALE_CUSTOMER_LINKED} na mesma transação, com a venda em {@code
 * entityId} e o cliente (id e nome) em {@code details}. A ação não está na lista de exemplos do
 * §7.2 — o catálogo é aberto e o verbo segue o padrão de {@code SALE_ITEM_ADDED} (decisão
 * registrada no commit do passo 811). Devolve o agregado atualizado — quem monta a resposta é a API
 * (passo 811b).
 */
@ApplicationScoped
public class LinkCustomerUseCase {

  /**
   * Ação do vínculo de cliente: o §7.2 lista exemplos, não o catálogo fechado; o verbo segue o
   * padrão de {@code SALE_ITEM_ADDED}.
   */
  private static final String SALE_CUSTOMER_LINKED_ACTION = "SALE_CUSTOMER_LINKED";

  /** Alvo do evento: a venda que recebeu o cliente. */
  private static final String SALE_ENTITY_TYPE = "SALE";

  /** Guarda de posse e estado da venda (BR-11): a operação só segue na venda do caixa da sessão. */
  @Inject SaleAccessGuard saleAccessGuard;

  /** Porta da venda: o {@code update} confere a versão lida — o lock é o otimista, como no 808. */
  @Inject SaleStore saleStore;

  /** Porta do cliente: o vínculo precisa do ativo e da distinção entre desativado e inexistente. */
  @Inject CustomerStore customerStore;

  /** Auditoria do vínculo (§7.2), na transação da venda. */
  @Inject AuditRecorder auditRecorder;

  /**
   * 404 {@code SALE_NOT_FOUND} para venda inexistente; 403 {@code ACCESS_DENIED} para venda de
   * outro caixa; 409 {@code SALE_NOT_OPEN} para venda concluída; 400 {@code VALIDATION_ERROR} sem
   * cliente; 404 {@code CUSTOMER_NOT_FOUND} para cliente inexistente; 422 {@code CUSTOMER_INACTIVE}
   * para cliente desativado. Devolve o agregado com o cliente vinculado.
   */
  @Transactional
  public Sale execute(LinkCustomerCommand command) {
    Sale sale =
        SaleAccessGuard.requireOpen(
            saleAccessGuard.requireOwned(command.saleId(), command.cashRegisterId()));
    UUID customerId = requireCustomerId(command.customerId());
    CustomerSummary customer =
        customerStore
            .findAnyById(customerId)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        ErrorCode.CUSTOMER_NOT_FOUND,
                        "cliente %s não encontrado".formatted(customerId)));
    requireActive(customer);
    sale.linkCustomer(customerId);
    saleStore.update(sale);
    auditRecorder.record(
        SALE_CUSTOMER_LINKED_ACTION,
        SALE_ENTITY_TYPE,
        sale.id(),
        null,
        details(customer),
        sale.cashSessionId());

    return sale;
  }

  /** Cliente sem id não é vinculável: nulo é 400, como o valor ausente do desconto no 810. */
  private static UUID requireCustomerId(UUID customerId) {
    if (customerId == null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "cliente é obrigatório");
    }
    return customerId;
  }

  /**
   * Cliente desativado não entra na venda: 422 {@code CUSTOMER_INACTIVE}. O {@code active} e o
   * {@code deletedAt} caem juntos na desativação (502a), mas a checagem cobre os dois — qualquer
   * linha desativada é a mesma recusa.
   */
  private static void requireActive(CustomerSummary customer) {
    if (!customer.active() || customer.deletedAt() != null) {
      throw new BusinessException(
          ErrorCode.CUSTOMER_INACTIVE, "cliente %s está inativo".formatted(customer.id()));
    }
  }

  /**
   * Details do evento: o cliente vinculado, com o nome que o operador reconhece. O nome nunca é
   * nulo (a coluna é {@code not null}), então o mapa imutável do {@code Map.of} serve.
   */
  private static Map<String, Object> details(CustomerSummary customer) {
    return Map.of("customerId", customer.id(), "customerName", customer.name());
  }
}
