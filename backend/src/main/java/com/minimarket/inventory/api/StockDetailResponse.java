package com.minimarket.inventory.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Detalhe do estoque (§9.3, passo 704): os campos do item da lista — produto, saldo da loja e
 * estoque baixo — mais os últimos movimentos do ledger, do mais recente para o mais antigo.
 */
public record StockDetailResponse(
    UUID productId,
    String name,
    String barcode,
    String unit,
    BigDecimal quantity,
    BigDecimal minQuantity,
    boolean lowStock,
    List<StockMovementResponse> movements) {}
