package com.minimarket.cash.api;

import com.minimarket.cash.domain.CashSessionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Sessão de caixa em detalhe como a API devolve (§9.3, passo 612): a abertura e, quando fechada, a
 * conferência do fechamento. Os campos de fechamento são nulos enquanto a sessão está aberta — é o
 * fechamento (passo 611) que grava contado, esperado, diferença, observações e quem fechou. {@code
 * storeId}, {@code version} e os carimbos de criação/atualização são detalhe do banco. A projeção
 * {@code CashSessionSummary} de {@code application} é mapeada para cá; entidade JPA nunca vai a
 * JSON.
 */
public record CashSessionDetailResponse(
    UUID id,
    UUID cashRegisterId,
    CashSessionStatus status,
    Instant openedAt,
    UUID openedByUserId,
    BigDecimal openingAmount,
    Instant closedAt,
    UUID closedByUserId,
    BigDecimal countedAmount,
    BigDecimal expectedAmount,
    BigDecimal differenceAmount,
    String closingNotes) {}
