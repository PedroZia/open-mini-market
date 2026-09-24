package com.minimarket.sales.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.auth.application.AuthorizationService;
import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleStatus;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.Permission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cancela a venda aberta (passo 813, BR-07): a desistência não apaga nada — a venda fica no
 * histórico com status {@code CANCELLED}, motivo, autor e instante. Uma execução = uma transação
 * (§2.2, regra 6): a linha da venda e o evento saem juntos ou não saem.
 *
 * <p>A ordem das checagens é deliberada: a permissão {@code sale.cancel} vem primeiro (deny by
 * default, passo 305) — sem ela o operador recebe 403 {@code ACCESS_DENIED} sem que a venda seja
 * lida e sem descobrir se ela existe. Depois a {@link SaleAccessGuard} (BR-11, §9.4): venda
 * inexistente é 404 {@code SALE_NOT_FOUND} para qualquer sessão e venda de outro caixa é 403 {@code
 * ACCESS_DENIED} — sem bypass de gestão, como nas operações de item, desconto e cliente.
 *
 * <p>O cancelamento é idempotente por contrato <em>e</em> por estado (§8): a chave {@code
 * Idempotency-Key} cobre o retry da mesma requisição na API, e o estado cobre a segunda tentativa
 * com chave nova. Venda já {@code CANCELLED} é <strong>no-op</strong>: devolve a venda como está,
 * sem gravar e sem novo evento — o motivo e o autor do primeiro cancelamento são os que valem, e
 * repetir a desistência não é operação nova. Venda {@code COMPLETED} é 409 {@code
 * SALE_ALREADY_COMPLETED} do domínio: o pagamento já aconteceu e a correção dela é o estorno da
 * Fase 13, não o cancelamento. Só a venda {@code OPEN} transita.
 *
 * <p>O motivo é obrigatório (BR-04/§7.2: operação sem motivo não é auditável) — nulo ou em branco é
 * 400 {@code VALIDATION_ERROR}, a mesma checagem que a forma da API faz. O autor vem do {@code
 * OperationContext} pela API e o instante do {@link Clock} injetado, nunca de {@code Instant.now()}
 * espalhado: os dois entram na própria linha da venda, como o master pediu.
 *
 * <p>A leitura é por {@code findById}, não por {@code lockById}: a linha fica no contexto de
 * persistência e o {@code update} do 803 confere a versão no flush — quem alterou a mesma venda
 * entre a leitura e a gravação recebe 409 {@code CONCURRENT_MODIFICATION}, o caminho que o 814
 * exercita.
 *
 * <p>Auditoria (§7.2): {@code SALE_CANCELLED} na mesma transação, com o motivo em {@code reason} e
 * os totais da venda no estado em que ela parou em {@code details}. Devolve o agregado cancelado —
 * quem monta a resposta é a API.
 */
@ApplicationScoped
public class CancelSaleUseCase {

  /** Ação do cancelamento (§7.2). */
  private static final String SALE_CANCELLED_ACTION = "SALE_CANCELLED";

  /** Alvo do evento: a venda cancelada. */
  private static final String SALE_ENTITY_TYPE = "SALE";

  /** Guarda de posse e estado da venda (BR-11): a operação só segue na venda do caixa da sessão. */
  @Inject SaleAccessGuard saleAccessGuard;

  /** Porta da venda: o {@code update} confere a versão lida — o lock é o otimista, como no 808. */
  @Inject SaleStore saleStore;

  /** Auditoria do cancelamento (§7.2), na transação da venda. */
  @Inject AuditRecorder auditRecorder;

  /** Permissões efetivas de quem pede (passo 305): cancelar exige {@code sale.cancel}. */
  @Inject AuthorizationService authorizationService;

  /** Relógio da aplicação: o instante do cancelamento, nunca o {@code now()} do banco. */
  @Inject Clock clock;

  /**
   * 403 {@code ACCESS_DENIED} para sessão sem {@code sale.cancel}; 404 {@code SALE_NOT_FOUND} para
   * venda inexistente; 403 {@code ACCESS_DENIED} para venda de outro caixa; 400 {@code
   * VALIDATION_ERROR} para motivo ausente ou em branco; 409 {@code SALE_ALREADY_COMPLETED} para
   * venda concluída. Venda já cancelada é no-op: devolve o agregado sem gravar e sem evento.
   */
  @Transactional
  public Sale execute(CancelSaleCommand command) {
    authorizationService.require(Permission.SALE_CANCEL);
    Sale sale = saleAccessGuard.requireOwned(command.saleId(), command.cashRegisterId());
    if (sale.status() == SaleStatus.CANCELLED) {
      return sale;
    }
    String reason = requireReason(command.reason());
    sale.cancel(reason, command.cancelledByUserId(), clock.instant());
    saleStore.update(sale);
    auditRecorder.record(
        SALE_CANCELLED_ACTION, SALE_ENTITY_TYPE, sale.id(), sale.cancelReason(), details(sale));

    return sale;
  }

  /** BR-04: cancelamento sem motivo não é operação auditável — nulo ou em branco é 400. */
  private static String requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "motivo do cancelamento é obrigatório");
    }
    return reason;
  }

  /**
   * Details do evento: o estado em que a venda parou — status final, totais e contagem de itens. O
   * motivo vai em {@code reason} e o autor é o ator do próprio evento (§7.2). O mapa é ordenado e
   * mutável para acompanhar o formato dos demais eventos de venda.
   */
  private static Map<String, Object> details(Sale sale) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("status", sale.status().name());
    details.put("subtotal", sale.subtotal());
    details.put("discountAmount", sale.discountAmount());
    details.put("total", sale.total());
    details.put("itemCount", sale.itemCount());
    return details;
  }
}
