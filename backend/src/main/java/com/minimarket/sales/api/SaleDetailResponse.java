package com.minimarket.sales.api;

import com.minimarket.sales.domain.DiscountType;
import com.minimarket.sales.domain.SaleStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Venda em detalhe como a API devolve nas rotas de item (passo 809b), de desconto e de cliente
 * (passo 811b), no detalhe da consulta (passo 812) e nas rotas de pagamento (passo 905): o
 * cabeçalho — número, status, caixa/sessão/operador, cliente, totais, desconto, {@code itemCount} e
 * timestamps — mais os itens com o snapshot de cada um e os pagamentos da venda. É o corpo das
 * respostas 200 das operações da venda aberta e do 201 do pagamento: a venda é o recurso da
 * operação e o cliente vê os totais recalculados pelo servidor (BR-02, BR-12) sem uma segunda
 * chamada.
 *
 * <p>{@code paidAmount} e {@code changeAmount} são derivados dos pagamentos aprovados (BR-05) — o
 * troco da venda é a soma do troco deles — e {@code payments} traz cada pagamento na ordem em que o
 * operador o registrou, cancelados inclusive (status {@code CANCELLED}), porque o cancelamento não
 * apaga o rastro. {@code customerId} é nulo na venda anônima (vínculo do passo 811) e {@code
 * completedAt}, até a conclusão (passo 906). O cancelamento da venda (passo 813) aparece com o
 * motivo, o autor e o instante — todos nulos enquanto a venda não foi cancelada. O desconto aparece
 * como o agregado o guarda: tipo e valor informado (reais em {@code VALUE}, percentual em {@code
 * PERCENT}), motivo e o valor calculado pelo servidor em {@code discountAmount} (BR-03). {@code
 * storeId} e {@code version} são detalhe do banco e não entram; a projeção {@code Sale} do domínio
 * é mapeada para cá — entidade JPA nunca vai a JSON.
 */
public record SaleDetailResponse(
    UUID id,
    long number,
    SaleStatus status,
    UUID cashSessionId,
    UUID cashRegisterId,
    UUID operatorUserId,
    UUID customerId,
    BigDecimal subtotal,
    DiscountType discountType,
    BigDecimal discountValue,
    String discountReason,
    BigDecimal discountAmount,
    BigDecimal total,
    BigDecimal paidAmount,
    BigDecimal changeAmount,
    int itemCount,
    Instant createdAt,
    Instant completedAt,
    String cancelReason,
    UUID cancelledByUserId,
    Instant cancelledAt,
    List<SaleItemResponse> items,
    List<PaymentResponse> payments) {}
