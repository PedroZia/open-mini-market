package com.minimarket.sales.domain;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Agregado da venda (linha “Venda” do §4.4): itens com snapshot do produto (BR-01), desconto
 * calculado sempre no servidor (BR-03), totais derivados dos itens e do desconto (BR-02) e
 * pago/troco derivados dos pagamentos (BR-05) — nada de valor calculado vindo de fora. Java puro —
 * sem JPA, Quarkus, Jackson ou HTTP; quem grava é o caso de uso, a partir do passo 803.
 *
 * <p>A venda nasce {@link SaleStatus#OPEN} no caixa que a criou (BR-06) e só sai daí ao concluir
 * (passo 906, quando o pagamento cobre o total) ou ao cancelar (passo 813, enquanto não foi paga).
 * O cancelamento guarda motivo, autor e instante na própria venda — desistir não apaga o rastro — e
 * venda já concluída não se cancela (409 {@link ErrorCode#SALE_ALREADY_COMPLETED}; a correção dela
 * é o estorno da Fase 13). Depois de {@code COMPLETED} ou {@code CANCELLED} o agregado é imutável
 * (BR-07): item, desconto e cliente recusam mutação com {@link ErrorCode#BUSINESS_ERROR} (422), a
 * mesma convenção do {@code CashSession}. Dinheiro tem escala 2 com arredondamento {@code HALF_UP}
 * e quantidade escala 3 (§3 do plano).
 */
public final class Sale {

  private static final int MONEY_SCALE = 2;
  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
  private static final BigDecimal HUNDRED = new BigDecimal("100");

  private final UUID id;
  private final UUID storeId;
  private final long number;
  private final UUID cashSessionId;
  private final UUID cashRegisterId;
  private final UUID operatorUserId;
  private final String notes;
  private final Instant createdAt;
  private final List<SaleItem> items = new ArrayList<>();

  private SaleStatus status = SaleStatus.OPEN;
  private DiscountType discountType;
  private BigDecimal discountValue;
  private String discountReason;
  private BigDecimal subtotal = zeroMoney();
  private BigDecimal discountAmount = zeroMoney();
  private BigDecimal total = zeroMoney();
  private BigDecimal paidAmount = zeroMoney();
  private BigDecimal changeAmount = zeroMoney();
  private int itemCount;
  private UUID customerId;
  private Instant completedAt;
  private String cancelReason;
  private UUID cancelledByUserId;
  private Instant cancelledAt;

  /**
   * Venda nova, aberta e sem itens, na sessão de caixa do operador (BR-06). O número sequencial da
   * loja vem do alocador (passo 804) e o cliente é vinculado depois (passo 811), por isso não entra
   * aqui.
   *
   * @param id identificador da venda (UUIDv7 gerado na aplicação)
   * @param storeId loja dona da venda
   * @param number número sequencial da venda dentro da loja, positivo
   * @param cashSessionId sessão de caixa aberta que a criou
   * @param cashRegisterId caixa (registro) da sessão
   * @param operatorUserId operador que abriu a venda
   * @param notes observações livres da venda; nulo quando não há
   * @param createdAt instante da abertura
   * @throws BusinessException se faltar identificação ou o número não for positivo
   */
  public Sale(
      UUID id,
      UUID storeId,
      long number,
      UUID cashSessionId,
      UUID cashRegisterId,
      UUID operatorUserId,
      String notes,
      Instant createdAt) {
    if (id == null
        || storeId == null
        || cashSessionId == null
        || cashRegisterId == null
        || operatorUserId == null
        || createdAt == null) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR,
          "id, loja, sessão de caixa, caixa, operador e instante de criação são obrigatórios");
    }
    if (number <= 0) {
      throw new BusinessException(ErrorCode.BUSINESS_ERROR, "número da venda deve ser positivo");
    }
    this.id = id;
    this.storeId = storeId;
    this.number = number;
    this.cashSessionId = cashSessionId;
    this.cashRegisterId = cashRegisterId;
    this.operatorUserId = operatorUserId;
    this.notes = notes;
    this.createdAt = createdAt;
  }

  public UUID id() {
    return id;
  }

  public UUID storeId() {
    return storeId;
  }

  public long number() {
    return number;
  }

  public UUID cashSessionId() {
    return cashSessionId;
  }

  public UUID cashRegisterId() {
    return cashRegisterId;
  }

  public UUID operatorUserId() {
    return operatorUserId;
  }

  /** Cliente vinculado à venda; nulo na venda anônima (o vínculo nasce no passo 811). */
  public UUID customerId() {
    return customerId;
  }

  public SaleStatus status() {
    return status;
  }

  /** Itens na ordem de inclusão; a cópia protege a lista do agregado. */
  public List<SaleItem> items() {
    return List.copyOf(items);
  }

  public DiscountType discountType() {
    return discountType;
  }

  /** Valor informado do desconto: reais em {@code VALUE}, percentual em {@code PERCENT}. */
  public BigDecimal discountValue() {
    return discountValue;
  }

  /** Desconto calculado pelo servidor a partir do tipo e do valor (BR-03). */
  public BigDecimal discountAmount() {
    return discountAmount;
  }

  /** Motivo do desconto; a obrigatoriedade e o limite da loja são do passo 810. */
  public String discountReason() {
    return discountReason;
  }

  /** Σ do total das linhas (BR-02). */
  public BigDecimal subtotal() {
    return subtotal;
  }

  /**
   * {@code max(subtotal − discount_amount, 0)} (BR-02): desconto maior que o subtotal zera o total,
   * nunca deixa negativo.
   */
  public BigDecimal total() {
    return total;
  }

  /**
   * Σ dos pagamentos aprovados (BR-05): estado <em>derivado</em> dos pagamentos, nunca informado
   * pelo cliente (BR-12) — quem escreve é {@link #applyPaymentTotals(PaymentTotals)} com a conta
   * que o caso de uso (passo 904) apurou. Zero enquanto não há pagamento aprovado.
   */
  public BigDecimal paidAmount() {
    return paidAmount;
  }

  /** Σ do troco dos pagamentos aprovados; zero sem pagamento em dinheiro (BR-05). */
  public BigDecimal changeAmount() {
    return changeAmount;
  }

  /**
   * Quantidade de linhas da venda. Venda por peso tem quantidade fracionada, então o contador é de
   * itens, não de unidades.
   */
  public int itemCount() {
    return itemCount;
  }

  public String notes() {
    return notes;
  }

  public Instant createdAt() {
    return createdAt;
  }

  /** Instante da conclusão; nulo enquanto a venda não foi concluída. */
  public Instant completedAt() {
    return completedAt;
  }

  /** Motivo do cancelamento; nulo enquanto a venda não foi cancelada (passo 813). */
  public String cancelReason() {
    return cancelReason;
  }

  /** Quem cancelou a venda; nulo enquanto ela não foi cancelada (passo 813). */
  public UUID cancelledByUserId() {
    return cancelledByUserId;
  }

  /** Instante do cancelamento; nulo enquanto a venda não foi cancelada (passo 813). */
  public Instant cancelledAt() {
    return cancelledAt;
  }

  /**
   * Inclui o produto na venda com o snapshot do momento da inclusão (BR-01). Se o produto já está
   * na venda, soma a quantidade no item existente e mantém o snapshot dele — o preço que vale é o
   * que foi capturado na primeira inclusão.
   *
   * @param barcode código de barras do snapshot; nulo quando o produto não tem código
   * @param quantity quantidade vendida, maior que zero
   * @throws BusinessException se a venda não estiver aberta ou o snapshot/quantidade forem
   *     inválidos
   */
  public void addItem(
      UUID productId,
      String barcode,
      String name,
      String unit,
      BigDecimal unitPrice,
      BigDecimal quantity) {
    requireOpen();
    SaleItem incoming = new SaleItem(productId, barcode, name, unit, unitPrice, quantity);
    SaleItem existing = findItem(productId);
    if (existing == null) {
      items.add(incoming);
    } else {
      existing.changeQuantity(existing.quantity().add(incoming.quantity()));
    }
    recalculate();
  }

  /**
   * Troca a quantidade do item do produto; a quantidade continua maior que zero — zerar o item é
   * {@link #removeItem(UUID)}.
   *
   * @throws BusinessException se a venda não estiver aberta, o produto não estiver na venda ou a
   *     quantidade for inválida
   */
  public void changeQuantity(UUID productId, BigDecimal quantity) {
    requireOpen();
    requireItem(productId).changeQuantity(quantity);
    recalculate();
  }

  /**
   * Tira o item do produto da venda.
   *
   * @throws BusinessException se a venda não estiver aberta ou o produto não estiver na venda
   */
  public void removeItem(UUID productId) {
    requireOpen();
    items.remove(requireItem(productId));
    recalculate();
  }

  /**
   * Aplica o desconto da venda: guarda tipo, valor e motivo e recalcula o total. O desconto é
   * recalculado pelo servidor a partir do tipo e do valor (BR-03) — o cliente nunca manda o valor
   * final. A permissão de quem aplica, a obrigatoriedade do motivo e o limite da loja são checados
   * no caso de uso (passo 810).
   *
   * @param value reais quando {@code VALUE}, percentual quando {@code PERCENT}; maior que zero
   * @param reason motivo do desconto; nulo quando não informado
   * @throws BusinessException se a venda não estiver aberta ou o tipo/valor forem inválidos
   */
  public void applyDiscount(DiscountType type, BigDecimal value, String reason) {
    requireOpen();
    if (type == null) {
      throw new BusinessException(ErrorCode.BUSINESS_ERROR, "tipo de desconto é obrigatório");
    }
    if (value == null || value.signum() <= 0) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR, "valor do desconto deve ser maior que zero");
    }
    this.discountType = type;
    this.discountValue = money(value);
    this.discountReason = reason;
    recalculate();
  }

  /**
   * Tira o desconto da venda: tipo, valor e motivo voltam a nulo e o total volta a ser o subtotal
   * (BR-02/BR-03) — quem recalcula é {@link #recalculate()}, como em qualquer mutação do agregado.
   * A permissão de quem remove e o evento de auditoria são do caso de uso (passo 810); remover
   * venda sem desconto é no-op de estado (nada a zerar), e quem decide não gravar é o caso de uso.
   *
   * @throws BusinessException se a venda não estiver aberta
   */
  public void removeDiscount() {
    requireOpen();
    this.discountType = null;
    this.discountValue = null;
    this.discountReason = null;
    recalculate();
  }

  /**
   * Vincula o cliente à venda aberta: a venda guarda um cliente por vez, então vincular outro
   * substitui o anterior. Quem confere se o cliente existe e está ativo é o caso de uso (passo 811)
   * — aqui só a venda aberta é exigida, e a venda anônima continua válida.
   *
   * @param customerId cliente a vincular, obrigatório
   * @throws BusinessException se a venda não estiver aberta ou o cliente não for informado
   */
  public void linkCustomer(UUID customerId) {
    requireOpen();
    if (customerId == null) {
      throw new BusinessException(ErrorCode.BUSINESS_ERROR, "cliente é obrigatório");
    }
    this.customerId = customerId;
  }

  /**
   * Desvincula o cliente da venda aberta: ela volta a ser anônima. Venda sem cliente vinculado é
   * no-op de estado (nada a zerar) e quem decide não gravar é o caso de uso, como na remoção do
   * desconto (passo 810).
   *
   * @throws BusinessException se a venda não estiver aberta
   */
  public void unlinkCustomer() {
    requireOpen();
    this.customerId = null;
  }

  /**
   * Deriva do estado atual os totais que ninguém escreve de fora: {@code subtotal} (Σ das linhas),
   * {@code item_count}, {@code discount_amount} (do tipo e do valor, BR-03) e {@code total}
   * (BR-02). É idempotente — repetir não muda nada, porque só lê itens e desconto.
   */
  public void recalculate() {
    BigDecimal itemsTotal = zeroMoney();
    for (SaleItem item : items) {
      itemsTotal = itemsTotal.add(item.lineTotal());
    }
    this.subtotal = itemsTotal;
    this.discountAmount = computedDiscountAmount(itemsTotal);
    this.total = money(this.subtotal.subtract(this.discountAmount).max(BigDecimal.ZERO));
    this.itemCount = items.size();
  }

  /**
   * Aplica os totais derivados dos pagamentos (BR-05/BR-12): {@code paidAmount} é a soma dos
   * aprovados e {@code changeAmount} a soma do troco deles. Ninguém calcula isso de fora — o caso
   * de uso (passo 904) entrega o {@link PaymentTotals} já somado e a venda só guarda; o cliente
   * nunca manda valor pago (BR-12).
   *
   * <p>Sem guarda de {@code OPEN} de propósito: não é mutação da venda, é estado derivado dos
   * pagamentos — o mapper rehidrata venda concluída ou cancelada (que já não aceita item, desconto
   * ou cliente) e as colunas {@code paid_amount}/{@code change_amount} precisam voltar como
   * estavam, inclusive para um update posterior não zerá-las.
   *
   * @param totals totais dos pagamentos aprovados, obrigatórios
   * @throws BusinessException se os totais não forem informados
   */
  public void applyPaymentTotals(PaymentTotals totals) {
    if (totals == null) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR, "totais dos pagamentos são obrigatórios");
    }
    this.paidAmount = totals.paidAmount();
    this.changeAmount = totals.changeAmount();
  }

  /**
   * O total pago cobre a venda? Compara a soma dos pagamentos aprovados (passo 903) com o total:
   * paga quando cobre ou supera — o troco (BR-05) é conta do pagamento em dinheiro, não da venda.
   *
   * @param paidAmount valor pago, zero ou positivo
   * @throws BusinessException se o valor pago não for informado
   */
  public boolean isPaidBy(BigDecimal paidAmount) {
    if (paidAmount == null) {
      throw new BusinessException(ErrorCode.BUSINESS_ERROR, "valor pago é obrigatório");
    }
    return money(paidAmount).compareTo(total) >= 0;
  }

  /**
   * Conclui a venda aberta e grava o instante. A conferência de que o pagamento cobre o total
   * (BR-05) é do caso de uso (passo 906) — aqui só a transição {@code OPEN → COMPLETED}, depois da
   * qual o agregado não aceita mais mutação (BR-07).
   *
   * @param completedAt instante da conclusão
   * @throws BusinessException se a venda não estiver aberta ou o instante não for informado
   */
  public void complete(Instant completedAt) {
    if (status != SaleStatus.OPEN) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR, "venda não está aberta para ser concluída");
    }
    if (completedAt == null) {
      throw new BusinessException(ErrorCode.BUSINESS_ERROR, "instante de conclusão é obrigatório");
    }
    this.completedAt = completedAt;
    this.status = SaleStatus.COMPLETED;
  }

  /**
   * Cancela a venda aberta (passo 813, BR-07): a desistência não apaga nada — a venda fica no
   * histórico com o motivo, o autor e o instante do cancelamento. Quem confere a permissão {@code
   * sale.cancel}, a posse da venda (BR-11) e a obrigatoriedade do motivo é o caso de uso; aqui só a
   * transição {@code OPEN → CANCELLED}, depois da qual o agregado não aceita mais mutação (BR-07).
   *
   * <p>Venda já concluída não se cancela: o pagamento e a baixa de estoque aconteceram e a correção
   * é o estorno da Fase 13 — é conflito de estado, {@link ErrorCode#SALE_ALREADY_COMPLETED} (409),
   * não regra de negócio. Venda já cancelada cai em {@link #requireOpen()} (422); quem trata a
   * repetição como no-op de estado é o caso de uso.
   *
   * @param reason motivo do cancelamento, não vazio
   * @param cancelledByUserId autor do cancelamento, obrigatório
   * @param cancelledAt instante do cancelamento, obrigatório
   * @throws ConflictException se a venda já estiver concluída
   * @throws BusinessException se a venda não estiver aberta ou faltar motivo, autor ou instante
   */
  public void cancel(String reason, UUID cancelledByUserId, Instant cancelledAt) {
    if (status == SaleStatus.COMPLETED) {
      throw new ConflictException(
          ErrorCode.SALE_ALREADY_COMPLETED,
          "venda %s já foi concluída em %s e não pode ser cancelada".formatted(id, completedAt));
    }
    requireOpen();
    if (reason == null || reason.isBlank()) {
      throw new BusinessException(ErrorCode.BUSINESS_ERROR, "motivo do cancelamento é obrigatório");
    }
    if (cancelledByUserId == null || cancelledAt == null) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR, "autor e instante do cancelamento são obrigatórios");
    }
    this.cancelReason = reason;
    this.cancelledByUserId = cancelledByUserId;
    this.cancelledAt = cancelledAt;
    this.status = SaleStatus.CANCELLED;
  }

  /** Venda concluída ou cancelada é imutável (BR-07). */
  private void requireOpen() {
    if (status != SaleStatus.OPEN) {
      throw new BusinessException(
          ErrorCode.BUSINESS_ERROR, "venda não está aberta e não aceita alteração");
    }
  }

  private SaleItem findItem(UUID productId) {
    for (SaleItem item : items) {
      if (item.productId().equals(productId)) {
        return item;
      }
    }
    return null;
  }

  private SaleItem requireItem(UUID productId) {
    SaleItem item = findItem(productId);
    if (item == null) {
      throw new BusinessException(ErrorCode.BUSINESS_ERROR, "item não encontrado na venda");
    }
    return item;
  }

  /**
   * Desconto do servidor: o valor informado em {@code VALUE}, o percentual do subtotal em {@code
   * PERCENT} — sem desconto, zero.
   */
  private BigDecimal computedDiscountAmount(BigDecimal itemsTotal) {
    if (discountType == null) {
      return zeroMoney();
    }
    return switch (discountType) {
      case VALUE -> discountValue;
      case PERCENT -> itemsTotal.multiply(discountValue).divide(HUNDRED, MONEY_SCALE, ROUNDING);
    };
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(MONEY_SCALE, ROUNDING);
  }

  private static BigDecimal zeroMoney() {
    return BigDecimal.ZERO.setScale(MONEY_SCALE);
  }
}
