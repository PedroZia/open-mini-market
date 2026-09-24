package com.minimarket.cash.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Entrada do {@link RecordSupplyUseCase}: em qual caixa o dinheiro entra, o valor informado pelo
 * operador, o motivo obrigatório (BR-10) e quem executou o suprimento. O caso de uso não conhece o
 * {@code OperationContext}: a API lê o ator da requisição e o repassa aqui (passo 610); fora de
 * requisição HTTP (tarefa de sistema, teste) ele vem de quem chama.
 *
 * <p>{@code amount} chega como o operador digitou — positivo; o sinal do ledger é aplicado pelo
 * caso de uso, na convenção de {@code cash_movements} (suprimento positivo).
 */
public record RecordSupplyCommand(
    UUID cashRegisterId, BigDecimal amount, String reason, UUID performedByUserId) {}
