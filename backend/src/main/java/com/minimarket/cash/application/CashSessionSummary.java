package com.minimarket.cash.application;

import com.minimarket.cash.domain.CashSessionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Projeção da sessão de caixa para a porta {@link CashSessionStore}: sem entidade JPA atravessando
 * para {@code application}. Os campos de fechamento são nulos enquanto a sessão está aberta — o
 * passo 611 os preenche no fechamento; {@code version} é o lock otimista da linha.
 */
public record CashSessionSummary(
    UUID id,
    UUID storeId,
    UUID cashRegisterId,
    CashSessionStatus status,
    UUID openedByUserId,
    Instant openedAt,
    BigDecimal openingAmount,
    UUID closedByUserId,
    Instant closedAt,
    BigDecimal countedAmount,
    BigDecimal expectedAmount,
    BigDecimal differenceAmount,
    String closingNotes,
    Instant createdAt,
    Instant updatedAt,
    long version) {}
