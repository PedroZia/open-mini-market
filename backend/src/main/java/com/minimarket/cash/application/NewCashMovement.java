package com.minimarket.cash.application;

import com.minimarket.cash.domain.CashMovementType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Movimento novo do ledger do caixa para a porta {@link CashSessionStore}. {@code amount} é
 * assinado, na convenção da tabela: {@code OPENING}/{@code SALE}/{@code SUPPLY} positivos e {@code
 * WITHDRAWAL} negativo. {@code paymentMethod}, {@code referenceType}, {@code referenceId} e {@code
 * reason} são opcionais e descrevem a origem do movimento (venda, sangria, suprimento). {@code
 * createdAt} é o instante do movimento, do relógio do caso de uso (passo 606) — na abertura é o
 * mesmo do {@code opened_at} da sessão —, nunca o {@code now()} do banco.
 */
public record NewCashMovement(
    UUID storeId,
    UUID cashSessionId,
    CashMovementType type,
    BigDecimal amount,
    String paymentMethod,
    String referenceType,
    UUID referenceId,
    String reason,
    UUID createdByUserId,
    Instant createdAt) {}
