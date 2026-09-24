package com.minimarket.sales.api;

import com.minimarket.sales.domain.DiscountType;
import com.minimarket.sales.domain.SaleStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Venda em detalhe como a API devolve nas rotas de item (passo 809b), de desconto e de cliente
 * (passo 811b): o cabeçalho — número, status, caixa/sessão/operador, cliente, totais, desconto,
 * {@code itemCount} e timestamps — mais os itens com o snapshot de cada um. É o corpo das respostas
 * 200 das operações da venda aberta: a venda é o recurso da operação e o cliente vê os totais
 * recalculados pelo servidor (BR-02, BR-12) sem uma segunda chamada.
 *
 * <p>O passo 812 (consulta de venda) reusa este DTO para o {@code GET /sales/{id}}; os pagamentos
 * entram na Fase 9, quando existirem. {@code customerId} é nulo na venda anônima (vínculo do passo
 * 811) e {@code completedAt}, até a conclusão (passo 906). O cancelamento (passo 813) aparece com o
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
    int itemCount,
    Instant createdAt,
    Instant completedAt,
    String cancelReason,
    UUID cancelledByUserId,
    Instant cancelledAt,
    List<SaleItemResponse> items) {}
