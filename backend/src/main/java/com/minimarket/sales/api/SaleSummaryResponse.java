package com.minimarket.sales.api;

import com.minimarket.sales.domain.SaleStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Venda no histórico (passo 812): o cabeçalho de cada linha da listagem paginada, sem os itens (o
 * detalhe com itens é do {@code GET /sales/{id}}, que devolve o {@link SaleDetailResponse}). Só os
 * campos do contrato: {@code storeId}, {@code version} e os valores de pagamento são detalhe do
 * banco — {@code paidAmount}/{@code changeAmount} chegam na Fase 9, quando existir pagamento. A
 * projeção {@link com.minimarket.sales.application.SaleSummary} é mapeada para cá; entidade JPA
 * nunca vai a JSON.
 */
public record SaleSummaryResponse(
    UUID id,
    long number,
    SaleStatus status,
    UUID cashSessionId,
    UUID cashRegisterId,
    UUID operatorUserId,
    UUID customerId,
    BigDecimal subtotal,
    BigDecimal discountAmount,
    BigDecimal total,
    int itemCount,
    Instant createdAt,
    Instant completedAt) {}
