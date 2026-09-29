package com.minimarket.reports.api;

import java.math.BigDecimal;

/**
 * Grupo do resumo de vendas no contrato (§9.3, passo 1212a): a chave da dimensão e o agregado.
 * {@code key} é a data UTC ({@code yyyy-MM-dd}) no {@code day}, o id do operador no {@code
 * operator} e o nome da forma ({@code CASH|PIX|DEBIT|CREDIT|VOUCHER}) no {@code paymentMethod}.
 */
public record SalesSummaryGroupResponse(String key, long salesCount, BigDecimal total) {}
