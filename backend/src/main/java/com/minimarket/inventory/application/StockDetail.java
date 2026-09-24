package com.minimarket.inventory.application;

import java.util.List;

/**
 * Detalhe do estoque de um produto (passo 704): o item da consulta (produto + saldo + estoque
 * baixo) com os últimos movimentos do ledger, do mais recente para o mais antigo.
 */
public record StockDetail(StockItemSummary item, List<StockMovementSummary> movements) {}
