package com.minimarket.cash.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Sessão nova para a porta {@link CashSessionStore}: carrega o caixa, o operador e o instante da
 * abertura. {@code openingAmount} é o fundo de troco informado na abertura — a validação de {@code
 * >= 0} é do caso de uso (606), com o check constraint da tabela como backstop.
 */
public record NewCashSession(
    UUID storeId,
    UUID cashRegisterId,
    UUID openedByUserId,
    Instant openedAt,
    BigDecimal openingAmount) {}
