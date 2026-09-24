package com.minimarket.cash.api;

import com.minimarket.cash.domain.CashMovementType;
import com.minimarket.cash.domain.CashSessionStatus;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Resumo do fechamento como a API devolve (§9.3, passo 612): esperado × contado com os totais por
 * tipo de movimento, os quatro sempre presentes (zero quando não houve movimento). O {@code
 * expectedAmount} é recalculado pelo servidor (BR-12) e {@code countedAmount}/{@code
 * differenceAmount} são nulos enquanto a sessão está aberta.
 *
 * <p>O {@code paymentsByMethod} (passo 909) é a quebra das vendas da sessão por forma de pagamento
 * — as cinco formas sempre presentes, com o nome como chave, na ordem do enum — para o fechamento
 * mostrar que a venda no cartão não entrou na gaveta. A projeção {@code CashSessionSummaryView} de
 * {@code application} é mapeada para cá; entidade JPA nunca vai a JSON.
 */
public record CashSessionSummaryResponse(
    UUID sessionId,
    CashSessionStatus status,
    BigDecimal openingAmount,
    BigDecimal expectedAmount,
    BigDecimal countedAmount,
    BigDecimal differenceAmount,
    Map<CashMovementType, BigDecimal> totalsByType,
    Map<String, BigDecimal> paymentsByMethod) {}
