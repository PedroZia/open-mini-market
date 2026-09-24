package com.minimarket.sales.application;

import com.github.f4b6a3.uuid.UuidCreator;
import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.auth.application.AuthorizationService;
import com.minimarket.sales.domain.Payment;
import com.minimarket.sales.domain.PaymentMethod;
import com.minimarket.sales.domain.PaymentTotals;
import com.minimarket.sales.domain.Sale;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.Permission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registra o pagamento na venda aberta (passo 904, BR-05/BR-12): valor positivo, dentro do que
 * falta cobrar, com troco calculado pelo servidor quando for dinheiro, e a venda fica com o total
 * pago e o troco atualizados. Uma execução = uma transação (§2.2, regra 6): a linha do pagamento, o
 * pago/troco da venda e o evento saem juntos ou não saem.
 *
 * <p>A ordem das checagens é deliberada: a {@link SaleAccessGuard} (BR-11, §9.4) vem primeiro —
 * venda inexistente é 404 {@code SALE_NOT_FOUND} para qualquer sessão, venda de outro caixa é 403
 * {@code ACCESS_DENIED} e venda fora de {@code OPEN} é 409 {@code SALE_NOT_OPEN}, conferido antes
 * de mutar o agregado — e depois a permissão {@code payment.add} (backstop do passo 305, como no
 * desconto): a API barra antes pelo {@code RequirePermission} e o caso de uso repete a checagem, de
 * modo que ninguém sem a permissão registra pagamento. Só então a forma do comando (forma e valor
 * ausentes ou não positivos são 400 {@code VALIDATION_ERROR}) e as regras do dinheiro.
 *
 * <p>Nenhum pagamento excede o restante da venda ({@code total − Σ aprovados}, BR-05): cartão acima
 * do que falta é 422 {@code PAYMENT_EXCEEDS_TOTAL} — valor a mais em dinheiro é troco (valor
 * entregue), não valor pago. Em {@code CASH} o valor entregue é obrigatório e precisa cobrir o
 * pagamento; nas demais formas ele é recusado, porque troco só existe em dinheiro: 422 {@code
 * INVALID_TENDERED_AMOUNT} nos dois casos. O troco ({@code tendered − amount}) e o total pago
 * ({@code Σ aprovados}) são calculados pelo servidor (BR-12) — o cliente nunca os manda, e a venda
 * só recebe o derivado pronto ({@code Sale.applyPaymentTotals}).
 *
 * <p>A leitura é por {@code findById}, não por {@code lockById}: a linha fica no contexto de
 * persistência e o {@code update} do 803 confere a versão no flush — dois pagamentos simultâneos na
 * mesma venda são serializados por ali e o perdedor recebe 409 {@code CONCURRENT_MODIFICATION} sem
 * pagamento órfão.
 *
 * <p>Auditoria (§7.2): {@code PAYMENT_ADDED} na mesma transação, com a venda em {@code entityId} e
 * pagamento, forma, valor, valor entregue, troco do pagamento e total pago resultante da venda em
 * {@code details}. Devolve o agregado atualizado — quem monta a resposta é a API (passo 905).
 */
@ApplicationScoped
public class AddPaymentUseCase {

  /** Ação do pagamento registrado (§7.2). */
  private static final String PAYMENT_ADDED_ACTION = "PAYMENT_ADDED";

  /** Alvo do evento: a venda que recebeu o pagamento. */
  private static final String SALE_ENTITY_TYPE = "SALE";

  /** Guarda de posse e estado da venda (BR-11): só paga a venda aberta do caixa da sessão. */
  @Inject SaleAccessGuard saleAccessGuard;

  /** Porta da venda: o {@code update} confere a versão lida — o lock é o otimista, como no 808. */
  @Inject SaleStore saleStore;

  /** Porta dos pagamentos: a soma dos aprovados vem do que já está gravado (passo 903). */
  @Inject PaymentStore paymentStore;

  /** Auditoria do pagamento (§7.2), na transação do registro. */
  @Inject AuditRecorder auditRecorder;

  /**
   * Permissões efetivas de quem pede (passo 305): registrar pagamento exige {@code payment.add}.
   */
  @Inject AuthorizationService authorizationService;

  /** Relógio da aplicação: o instante do pagamento, nunca o {@code now()} do banco. */
  @Inject Clock clock;

  /**
   * 404 {@code SALE_NOT_FOUND} para venda inexistente; 403 {@code ACCESS_DENIED} para venda de
   * outro caixa ou sessão sem {@code payment.add}; 409 {@code SALE_NOT_OPEN} para venda concluída
   * ou cancelada; 400 {@code VALIDATION_ERROR} para forma ou valor ausentes/não positivos; 422
   * {@code PAYMENT_EXCEEDS_TOTAL} acima do restante; 422 {@code INVALID_TENDERED_AMOUNT} para o
   * valor entregue do dinheiro. Devolve o agregado com o pago/troco atualizados.
   */
  @Transactional
  public Sale execute(AddPaymentCommand command) {
    Sale sale =
        SaleAccessGuard.requireOpen(
            saleAccessGuard.requireOwned(command.saleId(), command.cashRegisterId()));
    authorizationService.require(Permission.PAYMENT_ADD);
    PaymentMethod method = requireMethod(command.method());
    BigDecimal amount = requireAmount(command.amount());
    List<Payment> payments = new ArrayList<>(paymentStore.listBySale(sale.id()));
    requireFitsRemaining(PaymentTotals.of(payments), amount, sale.total());
    BigDecimal tenderedAmount = requireTendered(method, amount, command.tenderedAmount());
    Payment payment =
        new Payment(
            UuidCreator.getTimeOrderedEpoch(),
            sale.id(),
            method,
            amount,
            tenderedAmount,
            command.createdByUserId(),
            clock.instant());
    paymentStore.insert(payment);
    payments.add(payment);
    sale.applyPaymentTotals(PaymentTotals.of(payments));
    saleStore.update(sale);
    auditRecorder.record(
        PAYMENT_ADDED_ACTION,
        SALE_ENTITY_TYPE,
        sale.id(),
        null,
        details(payment, sale),
        sale.cashSessionId());

    return sale;
  }

  /** Pagamento sem forma não é aplicável; o domínio recusaria com 422 e a forma é 400. */
  private static PaymentMethod requireMethod(PaymentMethod method) {
    if (method == null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "forma de pagamento é obrigatória");
    }
    return method;
  }

  /** Pagamento de valor zero ou negativo não existe (BR-05): a forma recusa com 400. */
  private static BigDecimal requireAmount(BigDecimal amount) {
    if (amount == null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "valor do pagamento é obrigatório");
    }
    if (amount.signum() <= 0) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "valor do pagamento deve ser maior que zero");
    }
    return amount;
  }

  /**
   * Nenhum pagamento pode exceder o restante da venda (BR-05): dinheiro a mais é troco no próprio
   * pagamento (valor entregue), nunca valor pago — e cartão acima do que falta é recusado. A conta
   * é do {@link PaymentTotals} e a violação vira 422 {@code PAYMENT_EXCEEDS_TOTAL}, o código
   * estável que o operador vê; a recusa genérica do domínio fica como backstop. O valor não
   * positivo já foi barrado como 400 antes daqui.
   */
  private static void requireFitsRemaining(
      PaymentTotals totals, BigDecimal amount, BigDecimal total) {
    try {
      totals.requireFitsRemaining(amount, total);
    } catch (BusinessException violation) {
      throw new BusinessException(ErrorCode.PAYMENT_EXCEEDS_TOTAL, violation.getMessage());
    }
  }

  /**
   * Valor entregue do dinheiro (BR-05): em {@code CASH} é obrigatório e precisa cobrir o pagamento;
   * nas demais formas é recusado, porque troco só existe em dinheiro. A violação é 422 {@code
   * INVALID_TENDERED_AMOUNT} — o domínio repete a checagem no construtor do {@link Payment} e a
   * recusa genérica dele (422 {@code BUSINESS_ERROR}) fica como backstop. Devolve o valor entregue
   * quando for dinheiro e nulo nas demais formas.
   */
  private static BigDecimal requireTendered(
      PaymentMethod method, BigDecimal amount, BigDecimal tenderedAmount) {
    if (method != PaymentMethod.CASH) {
      if (tenderedAmount != null) {
        throw new BusinessException(
            ErrorCode.INVALID_TENDERED_AMOUNT,
            "valor entregue só é aceito em pagamento em dinheiro");
      }
      return null;
    }
    if (tenderedAmount == null) {
      throw new BusinessException(
          ErrorCode.INVALID_TENDERED_AMOUNT, "pagamento em dinheiro exige o valor entregue");
    }
    if (tenderedAmount.compareTo(amount) < 0) {
      throw new BusinessException(
          ErrorCode.INVALID_TENDERED_AMOUNT, "valor entregue não cobre o pagamento em dinheiro");
    }
    return tenderedAmount;
  }

  /**
   * Details do evento: o pagamento como o domínio o guardou (valor, valor entregue e troco
   * calculados pelo servidor) e o total pago resultante da venda. O mapa é ordenado e mutável
   * porque o valor entregue é nulo fora do dinheiro — {@code Map.of} recusaria.
   */
  private static Map<String, Object> details(Payment payment, Sale sale) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("paymentId", payment.id());
    details.put("method", payment.method().name());
    details.put("amount", payment.amount());
    details.put("tenderedAmount", payment.tenderedAmount());
    details.put("changeAmount", payment.changeAmount());
    details.put("paidAmount", sale.paidAmount());
    return details;
  }
}
