package com.minimarket.sales.api;

import com.minimarket.sales.domain.SaleStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Venda como a API devolve na abertura (passo 807): o cabeçalho que a TUI usa para saber em qual
 * caixa/sessão está vendendo e qual número a venda recebeu. Nasce {@code OPEN}, vazia e com os
 * totais zerados — itens (passo 809) e desconto (passo 811) mexem neles depois, sempre recalculados
 * pelo servidor (BR-02/BR-03/BR-12). Só os campos do contrato: {@code storeId} e {@code version}
 * são detalhe do banco e a projeção {@link com.minimarket.sales.domain.Sale} é mapeada para cá —
 * entidade JPA nunca vai a JSON.
 */
public record SaleResponse(
    UUID id,
    long number,
    SaleStatus status,
    UUID cashSessionId,
    UUID cashRegisterId,
    UUID operatorUserId,
    BigDecimal subtotal,
    BigDecimal discountAmount,
    BigDecimal total,
    int itemCount,
    Instant createdAt) {}
