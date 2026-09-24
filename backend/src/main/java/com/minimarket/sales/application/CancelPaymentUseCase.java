package com.minimarket.sales.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.auth.application.AuthorizationService;
import com.minimarket.sales.domain.Payment;
import com.minimarket.sales.domain.PaymentStatus;
import com.minimarket.sales.domain.PaymentTotals;
import com.minimarket.sales.domain.Sale;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import com.minimarket.shared.domain.Permission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cancela o pagamento da venda aberta (passo 905): desfazer um pagamento é cancelar — o pagamento
 * não é editável (passo 902) — e a venda volta ao pago que restar dos aprovados. Uma execução = uma
 * transação (§2.2, regra 6): a linha do pagamento, o {@code paid_amount} recalculado da venda e o
 * evento saem juntos ou não saem.
 *
 * <p>A ordem das checagens é a mesma do registro (passo 904): a {@link SaleAccessGuard} primeiro
 * (BR-11, §9.4) — venda inexistente é 404 {@code SALE_NOT_FOUND} para qualquer sessão, venda de
 * outro caixa é 403 {@code ACCESS_DENIED} e venda fora de {@code OPEN} é 409 {@code SALE_NOT_OPEN}
 * — e depois a permissão {@code payment.add} como backstop: desfazer um pagamento é tão sensível
 * quanto fazê-lo, a mesma escolha do desconto (passo 810) — a API barra antes pelo {@code
 * RequirePermission} e o caso de uso repete a checagem.
 *
 * <p>O pagamento é procurado entre os da venda: id que não está nela é 404 {@code
 * PAYMENT_NOT_FOUND} — quem não é dono da venda não descobre pagamento alheio e a resposta não
 * distingue pagamento inexistente de pagamento de outra venda. Pagamento já {@code CANCELLED} é
 * <strong>no-op</strong>: devolve a venda como está, sem gravar e sem novo evento — o primeiro
 * instante é o que vale e repetir o {@code DELETE} é inofensivo, como remover desconto sem desconto
 * (passo 810).
 *
 * <p>O pago e o troco da venda são recalculados pelo servidor (BR-05, BR-12) a partir dos
 * pagamentos que restaram aprovados ({@link PaymentTotals#of(List)}): o cliente nunca manda valor.
 * A leitura da venda é por {@code findById} e o {@code update} do 803 confere a versão no flush —
 * dois cancelamentos simultâneos na mesma venda são serializados ali e o perdedor recebe 409 {@code
 * CONCURRENT_MODIFICATION}, como no registro.
 *
 * <p>Auditoria (§7.2): {@code PAYMENT_CANCELLED} na mesma transação, com a venda em {@code
 * entityId} e pagamento, forma, valor e total pago resultante da venda em {@code details}. Devolve
 * o agregado atualizado — quem monta a resposta é a API.
 */
@ApplicationScoped
public class CancelPaymentUseCase {

  /** Ação do pagamento cancelado (§7.2). */
  private static final String PAYMENT_CANCELLED_ACTION = "PAYMENT_CANCELLED";

  /** Alvo do evento: a venda dona do pagamento. */
  private static final String SALE_ENTITY_TYPE = "SALE";

  /** Guarda de posse e estado da venda (BR-11): só cancela pagamento da venda aberta da sessão. */
  @Inject SaleAccessGuard saleAccessGuard;

  /** Porta da venda: o {@code update} confere a versão lida — o lock é o otimista, como no 904. */
  @Inject SaleStore saleStore;

  /** Porta dos pagamentos: é dela que sai o pagamento a cancelar e a nova soma dos aprovados. */
  @Inject PaymentStore paymentStore;

  /** Auditoria do cancelamento (§7.2), na transação da operação. */
  @Inject AuditRecorder auditRecorder;

  /** Permissões efetivas de quem pede (passo 305): cancelar pagamento exige {@code payment.add}. */
  @Inject AuthorizationService authorizationService;

  /** Relógio da aplicação: o instante do cancelamento, nunca o {@code now()} do banco. */
  @Inject Clock clock;

  /**
   * 404 {@code SALE_NOT_FOUND} para venda inexistente; 403 {@code ACCESS_DENIED} para venda de
   * outro caixa ou sessão sem {@code payment.add}; 409 {@code SALE_NOT_OPEN} para venda concluída
   * ou cancelada; 404 {@code PAYMENT_NOT_FOUND} para pagamento que não está na venda. Pagamento já
   * cancelado é no-op: devolve o agregado sem gravar e sem evento.
   */
  @Transactional
  public Sale execute(CancelPaymentCommand command) {
    Sale sale =
        SaleAccessGuard.requireOpen(
            saleAccessGuard.requireOwned(command.saleId(), command.cashRegisterId()));
    authorizationService.require(Permission.PAYMENT_ADD);
    List<Payment> payments = new ArrayList<>(paymentStore.listBySale(sale.id()));
    Payment payment = requirePayment(payments, command.paymentId());
    if (payment.status() == PaymentStatus.CANCELLED) {
      return sale;
    }
    payment.cancel(clock.instant());
    paymentStore.cancel(payment);
    sale.applyPaymentTotals(PaymentTotals.of(payments));
    saleStore.update(sale);
    auditRecorder.record(
        PAYMENT_CANCELLED_ACTION, SALE_ENTITY_TYPE, sale.id(), null, details(payment, sale));

    return sale;
  }

  /**
   * Pagamento da venda pelo id: 404 {@code PAYMENT_NOT_FOUND} quando não está na lista — o
   * pagamento de outra venda não é alcançável por esta rota e a resposta não revela que ele existe.
   */
  private static Payment requirePayment(List<Payment> payments, UUID paymentId) {
    for (Payment payment : payments) {
      if (payment.id().equals(paymentId)) {
        return payment;
      }
    }
    throw new NotFoundException(
        ErrorCode.PAYMENT_NOT_FOUND, "pagamento %s não está na venda".formatted(paymentId));
  }

  /**
   * Details do evento: o pagamento cancelado como o domínio o guardou (forma e valor) e o total
   * pago resultante da venda. O mapa é ordenado e mutável para acompanhar o formato dos demais
   * eventos de venda.
   */
  private static Map<String, Object> details(Payment payment, Sale sale) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("paymentId", payment.id());
    details.put("method", payment.method().name());
    details.put("amount", payment.amount());
    details.put("paidAmount", sale.paidAmount());
    return details;
  }
}
