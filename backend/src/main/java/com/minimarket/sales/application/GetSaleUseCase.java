package com.minimarket.sales.application;

import com.minimarket.sales.domain.Sale;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Detalhe da venda pelo id (passo 812, §9.3): a venda inteira — cabeçalho, itens, desconto e
 * cliente — para o caixa que a abriu ou para quem tem {@code report.read} (bypass de gestão). Quem
 * confere existência e visibilidade é a {@link SaleAccessGuard#requireVisible}: venda inexistente é
 * 404 {@code SALE_NOT_FOUND} e venda alheia sem a permissão de relatório é 403 {@code
 * ACCESS_DENIED}.
 *
 * <p>Leitura pura, sem {@code @Transactional}: não grava nada e a consulta é ida só ao banco, como
 * no {@code GetCashSessionUseCase} (passo 612). O agregado é reconstruído pelo adaptador do 803 e
 * quem monta a resposta é a API.
 */
@ApplicationScoped
public class GetSaleUseCase {

  /** Guarda de visibilidade da venda (BR-11, §9.4): posse da sessão ou bypass de gestão. */
  @Inject SaleAccessGuard saleAccessGuard;

  /**
   * 404 {@code SALE_NOT_FOUND} para venda inexistente; 403 {@code ACCESS_DENIED} para venda de
   * outro caixa sem {@code report.read}. Venda de qualquer status é consultável — o detalhe não
   * exige {@code OPEN}.
   */
  public Sale execute(GetSaleCommand command) {
    return saleAccessGuard.requireVisible(
        command.saleId(), command.cashRegisterId(), command.canReadAny());
  }
}
