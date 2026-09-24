package com.minimarket.sales.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.cash.application.CashSessionStore;
import com.minimarket.cash.application.NewCashMovement;
import com.minimarket.cash.domain.CashMovementType;
import com.minimarket.cash.domain.CashSessionStatus;
import com.minimarket.inventory.application.ApplyStockMovementCommand;
import com.minimarket.inventory.application.StockService;
import com.minimarket.inventory.domain.StockMovementType;
import com.minimarket.sales.domain.Payment;
import com.minimarket.sales.domain.PaymentMethod;
import com.minimarket.sales.domain.PaymentStatus;
import com.minimarket.sales.domain.PaymentTotals;
import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleItem;
import com.minimarket.sales.domain.SaleStatus;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Conclui a venda (passo 906, núcleo do sistema): a venda aberta e paga vira {@code COMPLETED} e os
 * efeitos colaterais da venda — baixa de estoque (BR-08/BR-09) e dinheiro no caixa (BR-10) —
 * acontecem na mesma transação do status e da auditoria (§2.2, regra 6; §7.1: falha ao auditar
 * derruba a conclusão). Qualquer falha no meio derruba tudo: nenhum item baixado, nenhum movimento
 * de caixa, nenhum evento e a venda segue {@code OPEN} — o rollback total é critério de aceite.
 *
 * <p>A ordem das checagens é deliberada: a venda é travada primeiro com {@code lockById} ({@code
 * SELECT ... FOR UPDATE}, passo 803) — é o lock que serializa duas conclusões da mesma venda (§8) e
 * a leitura sob ele já é o estado atual; id desconhecido é 404 {@code SALE_NOT_FOUND}. Com o
 * agregado em mãos, a posse (BR-11, §9.4) vem antes de revelar o estado: caixa da sessão nulo ou
 * diferente do caixa que abriu a venda é 403 {@code ACCESS_DENIED}. Venda já {@code COMPLETED} é
 * <strong>no-op</strong> (§8): devolve a venda como está, sem movimento novo, sem evento e sem
 * regravar — o retry com chave nova da API não move estoque duas vezes (o replay por chave é do
 * guard de idempotência). Venda {@code CANCELLED} é 409 {@code SALE_NOT_OPEN}, a mesma recusa das
 * demais operações de venda.
 *
 * <p>BR-05: a conclusão exige o pagamento cobrindo o total — a soma dos aprovados sai dos
 * pagamentos gravados (passo 903), nunca de valor informado pelo cliente (BR-12); insuficiente é
 * 422 {@code PAYMENT_INSUFFICIENT}. Na prática a soma é igual ao total, porque o {@code
 * AddPaymentUseCase} recusa excedente, mas o {@code ≥} é o mesmo do {@link Sale#isPaidBy}.
 *
 * <p>A sessão de caixa da venda é travada (passo 604) depois da venda e antes do estoque — a ordem
 * venda → sessão → linhas de estoque vale para todas as operações (§8), e o fechamento do caixa só
 * trava a sessão, então não há ciclo. Sessão que não está {@code OPEN} é 409 {@code
 * CASH_SESSION_REQUIRED}: sem essa trava, um fechamento concorrente poderia gravar o movimento
 * {@code SALE} em sessão já fechada, fora da conta do {@code expected_amount} — o passo 909 é quem
 * passa a recusar fechar com venda aberta; aqui a venda não pode concluir com o caixa fechado.
 *
 * <p>O estoque sai por {@link StockService#applyMovements} (passo 703), na mesma transação: um
 * {@code SALE_OUT} por item, com o delta negativo da quantidade vendida e a venda como referência,
 * ordenado por {@code product_id} pelo próprio serviço (§8, sem deadlock). BR-09: com {@code
 * allow_negative_stock=false}, o item sem saldo derruba o lote com 422 {@code INSUFFICIENT_STOCK} e
 * nada é baixado. O dinheiro entra pela parcela em {@code CASH} dos pagamentos aprovados — o valor
 * pago, nunca o entregue, que é troco e não receita — como movimento {@code SALE} do ledger do
 * caixa (BR-10); venda só em cartão/Pix não gera movimento, porque nenhum dinheiro passou pelo
 * caixa.
 *
 * <p>Auditoria (§7.2): {@code SALE_COMPLETED} na mesma transação, com a venda em {@code entityId} e
 * número, totais, troco e as formas de pagamento somadas em {@code details}. Sem backstop de
 * permissão aqui: a permissão {@code sale.complete} é aplicada pela API e o caso de uso é chamável
 * direto pelos testes de integração, como o {@code CreateSaleUseCase}. Devolve o agregado concluído
 * — quem monta a resposta é a API (passo 907).
 */
@ApplicationScoped
public class CompleteSaleUseCase {

  /** Ação da conclusão (§7.2). */
  private static final String SALE_COMPLETED_ACTION = "SALE_COMPLETED";

  /** Alvo do evento: a venda concluída. */
  private static final String SALE_ENTITY_TYPE = "SALE";

  /** Documento de origem do movimento de estoque e do movimento de caixa: a venda. */
  private static final String SALE_REFERENCE_TYPE = "SALE";

  /** Porta da venda: aqui a leitura é a travada, porque a conclusão é a operação mais sensível. */
  @Inject SaleStore saleStore;

  /** Porta dos pagamentos: a soma dos aprovados decide a conclusão (BR-05). */
  @Inject PaymentStore paymentStore;

  /** Porta do caixa: a sessão da venda é travada antes de o dinheiro entrar no ledger. */
  @Inject CashSessionStore cashSessionStore;

  /** Única porta de alteração de saldo (passo 703): os {@code SALE_OUT} dos itens da venda. */
  @Inject StockService stockService;

  /** Auditoria da conclusão (§7.2), na transação da venda. */
  @Inject AuditRecorder auditRecorder;

  /**
   * Relógio da aplicação: o instante da conclusão e dos movimentos, nunca o {@code now()} do banco.
   */
  @Inject Clock clock;

  /**
   * 404 {@code SALE_NOT_FOUND} para venda inexistente; 403 {@code ACCESS_DENIED} para venda de
   * outro caixa; 409 {@code SALE_NOT_OPEN} para venda cancelada; 422 {@code PAYMENT_INSUFFICIENT}
   * sem pagamento que cubra o total; 409 {@code CASH_SESSION_REQUIRED} para a sessão de caixa da
   * venda já fechada; 422 {@code INSUFFICIENT_STOCK} quando a loja não permite saldo negativo.
   * Venda já concluída é no-op: devolve o agregado sem gravar, sem movimento e sem evento.
   */
  @Transactional
  public Sale execute(CompleteSaleCommand command) {
    Sale sale = lockSale(command.saleId());
    SaleAccessGuard.requireOwned(sale, command.cashRegisterId());
    if (sale.status() == SaleStatus.COMPLETED) {
      return sale;
    }
    SaleAccessGuard.requireOpen(sale);
    List<Payment> payments = paymentStore.listBySale(sale.id());
    requirePaid(sale, PaymentTotals.of(payments));
    requireOpenCashSession(sale);
    Instant completedAt = clock.instant();
    stockService.applyMovements(stockMovements(sale, command.completedByUserId()));
    insertCashMovement(sale, cashPortion(payments), command.completedByUserId(), completedAt);
    sale.complete(completedAt);
    saleStore.update(sale);
    auditRecorder.record(
        SALE_COMPLETED_ACTION,
        SALE_ENTITY_TYPE,
        sale.id(),
        null,
        details(sale, payments),
        sale.cashSessionId());

    return sale;
  }

  /** Venda pelo id com o lock de escrita; sem ela não há o que concluir, para nenhuma sessão. */
  private Sale lockSale(UUID saleId) {
    return saleStore
        .lockById(saleId)
        .orElseThrow(
            () ->
                new NotFoundException(
                    ErrorCode.SALE_NOT_FOUND, "venda %s não encontrada".formatted(saleId)));
  }

  /**
   * BR-05: a venda só conclui com o pagamento cobrando o total. A soma vem dos pagamentos aprovados
   * gravados (passo 903) e a recusa é 422 {@code PAYMENT_INSUFFICIENT} — o código estável que a API
   * devolve (passo 907). O {@code ≥} é o mesmo do {@link Sale#isPaidBy}: pagar a mais não impede a
   * conclusão.
   */
  private static void requirePaid(Sale sale, PaymentTotals totals) {
    if (!sale.isPaidBy(totals.paidAmount())) {
      throw new BusinessException(
          ErrorCode.PAYMENT_INSUFFICIENT,
          "venda %s tem %s pago de %s".formatted(sale.id(), totals.paidAmount(), sale.total()));
    }
  }

  /**
   * A sessão da venda precisa estar aberta: é ela que recebe o movimento {@code SALE} e é o
   * fechamento concorrente que a trava decide (§8). O lock vale até o fim da transação — a sessão
   * não fecha entre esta checagem e o insert do movimento.
   */
  private void requireOpenCashSession(Sale sale) {
    boolean open =
        cashSessionStore
            .lockById(sale.cashSessionId())
            .filter(session -> session.status() == CashSessionStatus.OPEN)
            .isPresent();
    if (!open) {
      throw new ConflictException(
          ErrorCode.CASH_SESSION_REQUIRED,
          "sessão de caixa %s não está aberta para concluir a venda %s"
              .formatted(sale.cashSessionId(), sale.id()));
    }
  }

  /**
   * Um {@code SALE_OUT} por item, com o delta negativo da quantidade vendida e a venda como
   * referência; o lote é ordenado por {@code product_id} pelo {@link StockService} (§8) e roda na
   * transação da conclusão — a falha de um item derruba a venda inteira.
   */
  private static List<ApplyStockMovementCommand> stockMovements(Sale sale, UUID completedByUserId) {
    List<ApplyStockMovementCommand> commands = new ArrayList<>(sale.items().size());
    for (SaleItem item : sale.items()) {
      commands.add(
          new ApplyStockMovementCommand(
              item.productId(),
              StockMovementType.SALE_OUT,
              item.quantity().negate(),
              null,
              SALE_REFERENCE_TYPE,
              sale.id(),
              null,
              completedByUserId));
    }
    return commands;
  }

  /**
   * Dinheiro que entrou no caixa pela venda (BR-10): a parcela em {@code CASH} dos pagamentos
   * aprovados — o valor pago, nunca o entregue, que é troco do cliente — vira o movimento {@code
   * SALE} do ledger, positivo e com o instante da conclusão. Venda só em cartão/Pix não gera
   * movimento.
   */
  private void insertCashMovement(
      Sale sale, BigDecimal cashPortion, UUID completedByUserId, Instant completedAt) {
    if (cashPortion.signum() == 0) {
      return;
    }
    cashSessionStore.insertMovement(
        new NewCashMovement(
            sale.storeId(),
            sale.cashSessionId(),
            CashMovementType.SALE,
            cashPortion,
            PaymentMethod.CASH.name(),
            SALE_REFERENCE_TYPE,
            sale.id(),
            null,
            completedByUserId,
            completedAt));
  }

  /** Σ dos pagamentos aprovados em dinheiro: só eles passam pelo caixa (BR-05). */
  private static BigDecimal cashPortion(List<Payment> payments) {
    BigDecimal cash = BigDecimal.ZERO;
    for (Payment payment : payments) {
      if (payment.status() == PaymentStatus.APPROVED && payment.method() == PaymentMethod.CASH) {
        cash = cash.add(payment.amount());
      }
    }
    return cash;
  }

  /**
   * Details do evento: o número, os totais da venda no estado em que ela concluiu e o "como foi
   * pago" somado por forma. O mapa é ordenado e mutável para acompanhar o formato dos demais
   * eventos de venda.
   */
  private static Map<String, Object> details(Sale sale, List<Payment> payments) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("number", sale.number());
    details.put("total", sale.total());
    details.put("paidAmount", sale.paidAmount());
    details.put("changeAmount", sale.changeAmount());
    details.put("paymentsByMethod", paymentsByMethod(payments));
    return details;
  }

  /**
   * Σ dos aprovados por forma de pagamento, na ordem do enum e sem forma ausente — o rastro do que
   * compôs o pagamento, sem repetir cada linha de {@code payments}.
   */
  private static Map<String, BigDecimal> paymentsByMethod(List<Payment> payments) {
    Map<String, BigDecimal> byMethod = new LinkedHashMap<>();
    for (PaymentMethod method : PaymentMethod.values()) {
      BigDecimal sum = BigDecimal.ZERO;
      for (Payment payment : payments) {
        if (payment.status() == PaymentStatus.APPROVED && payment.method() == method) {
          sum = sum.add(payment.amount());
        }
      }
      if (sum.signum() > 0) {
        byMethod.put(method.name(), sum);
      }
    }
    return byMethod;
  }
}
