package com.minimarket.sales.application;

import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleItem;
import com.minimarket.sales.domain.SaleStatus;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.ForbiddenException;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/**
 * Guarda de acesso às operações de venda (BR-11, §9.4, passo 809): toda operação de venda só aceita
 * a venda pertencente ao caixa da sessão autenticada. O caixa da sessão chega no comando, montado
 * pela API a partir do {@code OperationContext} — nunca do corpo da requisição — e é comparado com
 * o caixa que abriu a venda; sessão sem vínculo de caixa ou vinculada a outro caixa é 403 {@code
 * ACCESS_DENIED}. A consulta de venda (passo 812) usa a mesma posse na variante {@link
 * #requireVisible(UUID, UUID, boolean)}, que abre exceção só para quem tem {@code report.read}.
 *
 * <p>A ordem das checagens é deliberada: venda inexistente é 404 {@code SALE_NOT_FOUND} para
 * qualquer sessão, e só depois a posse é conferida — quem não é dono não descobre status nem itens
 * da venda alheia, e a recusa por estado (409 {@code SALE_NOT_OPEN}, de {@link #requireOpen(Sale)})
 * nunca acontece antes da posse. A leitura é por {@link SaleStore#findById}: quem grava usa o mesmo
 * agregado e o {@code update} do 803 confere a versão no flush — o lock é o otimista, como no 808.
 */
@ApplicationScoped
public class SaleAccessGuard {

  /** Porta da venda: o agregado completo é o que as operações de item mutam. */
  @Inject SaleStore saleStore;

  /**
   * Venda que a sessão autenticada pode operar: 404 {@code SALE_NOT_FOUND} para id desconhecido e
   * 403 {@code ACCESS_DENIED} quando o caixa da sessão é nulo ou não é o caixa que abriu a venda.
   * Devolve o agregado lido para o caso de uso mutar e gravar.
   */
  public Sale requireOwned(UUID saleId, UUID cashRegisterId) {
    Sale sale =
        saleStore
            .findById(saleId)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        ErrorCode.SALE_NOT_FOUND, "venda %s não encontrada".formatted(saleId)));
    if (cashRegisterId == null || !cashRegisterId.equals(sale.cashRegisterId())) {
      throw new ForbiddenException(
          "venda %s não pertence ao caixa da sessão autenticada".formatted(saleId));
    }
    return sale;
  }

  /**
   * Venda que a sessão autenticada pode <em>ler</em> (passo 812, BR-11/§9.4): a mesma posse de
   * {@link #requireOwned} <em>ou</em> o bypass de gestão de quem tem {@code report.read} — o §4.5
   * não define permissão de leitura de venda e a consulta de retaguarda precisa enxergar a venda de
   * qualquer caixa; a permissão é resolvida pela API e chega no {@code canReadAny}. Venda
   * inexistente é 404 {@code SALE_NOT_FOUND} para qualquer sessão; sem posse e sem bypass, 403
   * {@code ACCESS_DENIED}. Leitura pura: não exige {@code OPEN} (venda concluída é consultável) e
   * não trava a linha — o lock continua sendo o otimista das mutações.
   */
  public Sale requireVisible(UUID saleId, UUID cashRegisterId, boolean canReadAny) {
    Sale sale =
        saleStore
            .findById(saleId)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        ErrorCode.SALE_NOT_FOUND, "venda %s não encontrada".formatted(saleId)));
    if (!canReadAny && (cashRegisterId == null || !cashRegisterId.equals(sale.cashRegisterId()))) {
      throw new ForbiddenException(
          "venda %s não pertence ao caixa da sessão autenticada".formatted(saleId));
    }
    return sale;
  }

  /**
   * Venda dona só aceita operação de item enquanto está {@code OPEN}: fora disso é 409 {@code
   * SALE_NOT_OPEN}, conferido <em>antes</em> de mutar o agregado — a recusa do domínio (422) fica
   * como backstop.
   */
  public static Sale requireOpen(Sale sale) {
    if (sale.status() != SaleStatus.OPEN) {
      throw new ConflictException(
          ErrorCode.SALE_NOT_OPEN,
          "venda %s está %s e não aceita alteração de itens".formatted(sale.id(), sale.status()));
    }
    return sale;
  }

  /**
   * Item da venda pelo produto: 404 {@code SALE_ITEM_NOT_FOUND} quando o produto não está na venda.
   * É a checagem antecipada das mutações de item — a recusa do domínio para o item ausente é {@code
   * BUSINESS_ERROR} (422) e fica como backstop; para o operador, o item que não está na venda é
   * recurso inexistente.
   */
  public static SaleItem requireItem(Sale sale, UUID productId) {
    for (SaleItem item : sale.items()) {
      if (item.productId().equals(productId)) {
        return item;
      }
    }
    throw new NotFoundException(
        ErrorCode.SALE_ITEM_NOT_FOUND,
        "item do produto %s não está na venda %s".formatted(productId, sale.id()));
  }
}
