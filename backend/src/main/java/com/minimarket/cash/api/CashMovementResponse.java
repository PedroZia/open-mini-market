package com.minimarket.cash.api;

import com.minimarket.cash.domain.CashMovementType;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Movimento de dinheiro como a API devolve (passo 610): o que o operador precisa ver para confirmar
 * a sangria ou o suprimento. {@code amount} é o valor <em>informado</em>, positivo — o sinal fica
 * no ledger ({@code cash_movements}) —, {@code expectedBefore}/{@code expectedAfter} são o saldo
 * esperado recalculado pelo servidor (BR-12) antes e depois do movimento e {@code aboveExpected} é
 * o alerta de sangria acima do esperado (sempre {@code false} no suprimento, que só aumenta o
 * esperado).
 *
 * <p>Só os campos do contrato: a projeção {@code CashMovementResult} de {@code application} é
 * mapeada para cá; entidade JPA nunca vai a JSON.
 */
public record CashMovementResponse(
    UUID sessionId,
    CashMovementType type,
    BigDecimal amount,
    String reason,
    BigDecimal expectedBefore,
    BigDecimal expectedAfter,
    boolean aboveExpected) {}
