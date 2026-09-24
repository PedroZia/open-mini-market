package com.minimarket.sales.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.catalog.application.ProductSummary;
import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleStatus;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bipe vira item (passo 808): resolve o produto, inclui o item na venda aberta com o snapshot do
 * momento (BR-01), recalcula os totais pelo agregado (BR-02) e audita {@code SALE_ITEM_ADDED}. Uma
 * execução = uma transação (§2.2, regra 6): linha do item, totais da venda e evento saem juntos ou
 * não saem.
 *
 * <p>A resolução é do servidor (BR-14): o barcode chega bruto e passa pela <em>mesma</em>
 * normalização do bipe (passo 409) — trim e sem espaços internos, que o leitor às vezes insere — e
 * vai à porta {@link ProductStore#findByBarcode}, que não enxerga o soft-deletado. Sem barcode, o
 * produto vem do id ({@link ProductStore#findById}, que enxerga o soft-deletado de propósito).
 * Código interno e etiqueta de balança ficam para o passo 1104b: aqui é só o código exato.
 *
 * <p>Produto inexistente — ou soft-deletado, que o barcode não alcança — é 404 {@code
 * PRODUCT_NOT_FOUND}, o mesmo do bipe. Produto <em>inativo</em> é 422 {@code PRODUCT_INACTIVE}: a
 * linha existe e o operador precisa saber que ela não vende, não que ela sumiu. Pelo barcode essa
 * distinção só aparece na janela em que o produto está {@code active=false} sem {@code deleted_at}
 * (a desativação do 412 grava os dois de uma vez) — o soft-deletado é invisível para a porta e cai
 * no 404.
 *
 * <p>A venda é lida por {@link SaleStore#findById}, não por {@code lockById}: a leitura deixa a
 * linha no contexto de persistência e o {@code update} do 803 confere a versão no flush — quem
 * alterou a mesma venda entre a leitura e a gravação recebe 409 {@code CONCURRENT_MODIFICATION}, o
 * caminho que o 814 exercita. Venda inexistente é 404 {@code SALE_NOT_FOUND}; venda fora de {@code
 * OPEN} é 409 {@code SALE_NOT_OPEN}, conferido <em>antes</em> de mutar o agregado — a recusa do
 * domínio (422) fica como backstop.
 *
 * <p>Item do produto que já está na venda é somado na mesma linha, mantendo o snapshot da primeira
 * inclusão (BR-01): o preço que vale é o capturado lá, nunca o do cadastro de agora. Quem decide
 * isso é o agregado ({@link Sale#addItem}); aqui não há aritmética — quantidade, total da linha,
 * subtotal e total são sempre do servidor (BR-02).
 *
 * <p>Auditoria (§7.2): {@code SALE_ITEM_ADDED} na mesma transação, com a venda em {@code entityId}
 * e produto, barcode, quantidade e os totais/itemCount resultantes em {@code details}. Devolve o
 * agregado atualizado — quem monta a resposta é a API (passo 809).
 */
@ApplicationScoped
public class AddSaleItemUseCase {

  /** Ação da inclusão de item (§7.2). */
  private static final String SALE_ITEM_ADDED_ACTION = "SALE_ITEM_ADDED";

  /** Alvo do evento de inclusão: a venda que recebeu o item. */
  private static final String SALE_ENTITY_TYPE = "SALE";

  /**
   * Porta da venda: {@code findById} para que o {@code update} confira a versão lida — o lock aqui
   * é o otimista, como no 814.
   */
  @Inject SaleStore saleStore;

  /** Porta do catálogo: o produto vem por barcode (409) ou por id, nunca do cliente. */
  @Inject ProductStore productStore;

  /** Auditoria da inclusão (§7.2), na transação do item. */
  @Inject AuditRecorder auditRecorder;

  /**
   * 400 {@code VALIDATION_ERROR} sem barcode e sem produto; 404 {@code PRODUCT_NOT_FOUND} para
   * produto inexistente; 422 {@code PRODUCT_INACTIVE} para produto desativado; 404 {@code
   * SALE_NOT_FOUND} para venda inexistente; 409 {@code SALE_NOT_OPEN} para venda concluída. Devolve
   * o agregado com o item incluído e os totais recalculados.
   */
  @Transactional
  public Sale execute(AddSaleItemCommand command) {
    ProductSummary product = resolveProduct(command);
    Sale sale = requireOpenSale(command.saleId());
    sale.addItem(
        product.id(),
        product.barcode(),
        product.name(),
        product.unit(),
        product.price(),
        command.quantity());
    saleStore.update(sale);
    auditRecorder.record(
        SALE_ITEM_ADDED_ACTION,
        SALE_ENTITY_TYPE,
        sale.id(),
        null,
        details(product, command.quantity(), sale));

    return sale;
  }

  /**
   * Produto do item, na ordem do comando: barcode preenchido manda (é o caminho do bipe), senão o
   * id; os dois ausentes é 400 — sem produto não há item. O snapshot que o agregado guarda é o da
   * projeção — inclusive o barcode, que pode ser nulo para produto sem código —, nunca a string
   * digitada no leitor.
   */
  private ProductSummary resolveProduct(AddSaleItemCommand command) {
    String barcode = normalizeBarcode(command.barcode());
    if (barcode != null) {
      return productStore
          .findByBarcode(barcode)
          .map(AddSaleItemUseCase::requireActive)
          .orElseThrow(
              () ->
                  new NotFoundException(
                      ErrorCode.PRODUCT_NOT_FOUND,
                      "produto com código de barras %s não encontrado".formatted(barcode)));
    }
    if (command.productId() != null) {
      UUID productId = command.productId();
      return productStore
          .findById(productId)
          .map(AddSaleItemUseCase::requireActive)
          .orElseThrow(
              () ->
                  new NotFoundException(
                      ErrorCode.PRODUCT_NOT_FOUND,
                      "produto %s não encontrado".formatted(productId)));
    }
    throw new BusinessException(
        ErrorCode.VALIDATION_ERROR, "código de barras ou id do produto é obrigatório");
  }

  /**
   * Mesma regra do {@code CreateProductUseCase} (passo 405) e do bipe (passo 409): trim, todos os
   * espaços internos fora e nulo quando em branco — barcode em branco é o mesmo que ausente, e aí o
   * produto vem do id.
   */
  private static String normalizeBarcode(String barcode) {
    if (barcode == null) {
      return null;
    }
    String normalized = barcode.trim().replaceAll("\\s+", "");
    return normalized.isEmpty() ? null : normalized;
  }

  /**
   * Produto inativo não entra na venda: 422 {@code PRODUCT_INACTIVE}. {@code deletedAt} preenchido
   * conta como inativo — o id alcança o soft-deletado, e vender o que foi desativado é a mesma
   * recusa (o barcode nem chega aqui, porque a porta não o enxerga).
   */
  private static ProductSummary requireActive(ProductSummary product) {
    if (!product.active() || product.deletedAt() != null) {
      throw new BusinessException(
          ErrorCode.PRODUCT_INACTIVE, "produto %s está inativo".formatted(product.id()));
    }
    return product;
  }

  /**
   * Venda que recebe o item: 404 {@code SALE_NOT_FOUND} para id desconhecido e 409 {@code
   * SALE_NOT_OPEN} quando ela não está aberta — a checagem é antecipada para a recusa não depender
   * do 422 do domínio, que fica como backstop.
   */
  private Sale requireOpenSale(UUID saleId) {
    Sale sale =
        saleStore
            .findById(saleId)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        ErrorCode.SALE_NOT_FOUND, "venda %s não encontrada".formatted(saleId)));
    if (sale.status() != SaleStatus.OPEN) {
      throw new ConflictException(
          ErrorCode.SALE_NOT_OPEN,
          "venda %s está %s e não aceita novos itens".formatted(sale.id(), sale.status()));
    }
    return sale;
  }

  /**
   * Details do evento: o produto e o que a inclusão deixou na venda — quantidade desta chamada (a
   * linha pode ter somado com o item que já estava lá) e os totais/itemCount resultantes. O mapa é
   * ordenado e mutável porque o {@code barcode} pode ser nulo — {@code Map.of} recusaria.
   */
  private static Map<String, Object> details(
      ProductSummary product, BigDecimal quantity, Sale sale) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("productId", product.id());
    details.put("barcode", product.barcode());
    details.put("quantity", quantity);
    details.put("subtotal", sale.subtotal());
    details.put("discountAmount", sale.discountAmount());
    details.put("total", sale.total());
    details.put("itemCount", sale.itemCount());
    return details;
  }
}
