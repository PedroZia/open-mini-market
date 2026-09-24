package com.minimarket.cash.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Entrada do {@link RecordWithdrawalUseCase}: de qual caixa o dinheiro sai, o valor informado pelo
 * operador, o motivo obrigatório (BR-10) e quem executou a sangria. O caso de uso não conhece o
 * {@code OperationContext}: a API lê o ator da requisição e o repassa aqui (passo 610); fora de
 * requisição HTTP (tarefa de sistema, teste) ele vem de quem chama.
 *
 * <p>{@code amount} chega como o operador digitou — positivo; o sinal do ledger é aplicado pelo
 * caso de uso, na convenção de {@code cash_movements} (sangria negativa).
 */
public record RecordWithdrawalCommand(
    UUID cashRegisterId, BigDecimal amount, String reason, UUID performedByUserId) {}
