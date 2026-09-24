package com.minimarket.cash.application;

import com.minimarket.cash.domain.CashMovementType;
import com.minimarket.cash.domain.CashSessionStatus;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Resumo do fechamento de uma sessão de caixa (passo 612): esperado × contado com os totais por
 * tipo de movimento — é o que a retaguarda confere na gaveta. O {@code expectedAmount} sai da regra
 * única do {@code CashSessionAmounts} sobre os totais que o banco somou ({@code OPENING} fora da
 * soma além de {@code openingAmount}, como no passo 608) e os totais vêm zero-preenchidos com os
 * quatro tipos; {@code countedAmount} e {@code differenceAmount} são os da sessão, nulos enquanto
 * ela está aberta.
 *
 * <p>O {@code paymentsByMethod} (passo 909) é a quebra das vendas da sessão por forma de pagamento
 * — sempre as cinco formas, zero-preenchidas, com o nome como chave — vinda da porta invertida
 * {@link SessionSalesLookup}; é ela que mostra que a venda no cartão não passou pela gaveta. A API
 * mapeia para o seu próprio record; entidade JPA nunca chega a {@code application}.
 */
public record CashSessionSummaryView(
    UUID sessionId,
    CashSessionStatus status,
    BigDecimal openingAmount,
    BigDecimal expectedAmount,
    BigDecimal countedAmount,
    BigDecimal differenceAmount,
    Map<CashMovementType, BigDecimal> totalsByType,
    Map<String, BigDecimal> paymentsByMethod) {}
