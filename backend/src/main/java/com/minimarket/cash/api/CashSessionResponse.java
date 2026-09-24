package com.minimarket.cash.api;

import com.minimarket.cash.domain.CashSessionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Sessão de caixa como a API devolve (§9.3, passo 607): só os campos do contrato — {@code storeId},
 * {@code version} e os campos de fechamento são detalhe do banco. A projeção {@code
 * CashSessionSummary} de {@code application} é mapeada para cá; entidade JPA nunca vai a JSON.
 */
public record CashSessionResponse(
    UUID id,
    UUID cashRegisterId,
    CashSessionStatus status,
    Instant openedAt,
    UUID openedByUserId,
    BigDecimal openingAmount) {}
