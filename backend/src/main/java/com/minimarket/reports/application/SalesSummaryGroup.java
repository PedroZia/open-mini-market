package com.minimarket.reports.application;

import java.math.BigDecimal;

/**
 * Grupo do resumo de vendas (passo 1212a): projeção mínima que a API mapeia para {@code
 * SalesSummaryGroupResponse}. {@code key} é a dimensão agrupada — a data UTC ({@code yyyy-MM-dd})
 * no {@code day}, o id do operador no {@code operator} e o nome da forma no {@code paymentMethod} —
 * e o par {@code salesCount}/{@code total} já vem agregado do banco, nada é recalculado aqui.
 */
public record SalesSummaryGroup(String key, long salesCount, BigDecimal total) {}
