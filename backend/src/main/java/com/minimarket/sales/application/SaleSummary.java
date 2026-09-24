package com.minimarket.sales.application;

import com.minimarket.sales.domain.SaleStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Projeção do cabeçalho da venda para a porta {@link SaleStore}: sem entidade JPA atravessando para
 * {@code application}. É o que a listagem e o histórico do passo 812 mostram — o detalhe com itens
 * sai do agregado em {@link SaleStore#findById}.
 *
 * <p>{@code customerId} (passo 811), {@code paidAmount} e {@code changeAmount} (passos 905+) são
 * colunas que o domínio ainda não preenche — até lá chegam nulas/zero, como a linha as guarda. Os
 * valores monetários são dinheiro em escala 2 (§3 do plano).
 */
public record SaleSummary(
    UUID id,
    UUID storeId,
    long number,
    SaleStatus status,
    UUID cashSessionId,
    UUID cashRegisterId,
    UUID operatorUserId,
    UUID customerId,
    BigDecimal subtotal,
    BigDecimal discountAmount,
    BigDecimal total,
    BigDecimal paidAmount,
    BigDecimal changeAmount,
    int itemCount,
    Instant createdAt,
    Instant completedAt) {}
