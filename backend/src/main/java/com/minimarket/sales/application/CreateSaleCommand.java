package com.minimarket.sales.application;

import java.util.UUID;

/**
 * Entrada do {@link CreateSaleUseCase}: o caixa ao qual a sessão autenticada está vinculada e o
 * operador que abre a venda. A API (passo 807) monta os dois do {@code OperationContext} — o corpo
 * da requisição não escolhe caixa nem operador (BR-11) e o número da venda vem do alocador, nunca
 * do cliente. {@code cashRegisterId} nulo é sessão sem vínculo de caixa: sem caixa não há venda
 * "solta" para abrir (BR-06), e o caso de uso recusa com 403.
 */
public record CreateSaleCommand(UUID cashRegisterId, UUID operatorUserId) {}
