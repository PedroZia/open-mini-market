package com.minimarket.sales.application;

import java.util.UUID;

/**
 * Entrada do {@link GetSaleUseCase} (passo 812): a venda alvo, o caixa da sessão autenticada e o
 * bypass de gestão.
 *
 * <p>{@code cashRegisterId} é o caixa da sessão, montado pela API a partir do {@code
 * OperationContext} — nunca do path nem da query (BR-11): a {@link SaleAccessGuard} só libera a
 * venda do próprio caixa. {@code canReadAny} é a permissão {@code report.read} já resolvida pela
 * API ({@code AuthorizationService}, passo 305): o §4.5 não tem permissão de leitura de venda e a
 * consulta de retaguarda precisa enxergar a venda de qualquer caixa, então quem tem a permissão de
 * relatório lê a venda alheia; sem ela, vale a posse.
 *
 * @param saleId venda consultada
 * @param cashRegisterId caixa da sessão autenticada; nulo é sessão sem vínculo de caixa
 * @param canReadAny a sessão tem {@code report.read} e pode ler venda de qualquer caixa?
 */
public record GetSaleCommand(UUID saleId, UUID cashRegisterId, boolean canReadAny) {}
