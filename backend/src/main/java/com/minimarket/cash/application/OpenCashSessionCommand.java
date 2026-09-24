package com.minimarket.cash.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Entrada do {@link OpenCashSessionUseCase}: o caixa escolhido pelo operador, o fundo de troco
 * informado e quem abriu. {@code authSessionId} é a sessão autenticada que pediu a abertura — a API
 * a lê do {@code OperationContext} (passo 607) e a repassa aqui; é nula fora de requisição HTTP
 * (tarefa interna, teste), quando não há sessão para vincular ao caixa.
 */
public record OpenCashSessionCommand(
    UUID cashRegisterId, BigDecimal openingAmount, UUID openedByUserId, UUID authSessionId) {}
