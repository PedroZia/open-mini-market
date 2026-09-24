package com.minimarket.inventory.application;

import com.minimarket.inventory.domain.StockMovementType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Porta de persistência do ledger de estoque (passo 702); o adaptador JPA fica em {@code
 * inventory.infrastructure}. Só tipos de aplicação atravessam: entidade JPA nunca chega aqui. O
 * ledger é append-only (§7.3): esta porta só insere e lê — não existe update nem delete.
 */
public interface StockMovementStore {

  /** Insere o movimento do ledger e devolve o id gerado (UUIDv7) pelo adaptador. */
  UUID insert(NewStockMovement movement);

  /**
   * Últimos movimentos do produto, do mais recente para o mais antigo ({@code created_at desc},
   * desempate por {@code id desc}) e no máximo {@code limit} linhas: é o histórico do detalhe de
   * estoque (passo 704).
   */
  List<StockMovementSummary> listByProduct(UUID productId, int limit);

  /**
   * Soma dos {@code quantity_delta} assinados dos movimentos do produto, por tipo: o saldo
   * reconstruído do ledger. Tipo sem movimento fica fora do mapa; produto sem movimento devolve
   * mapa vazio.
   */
  Map<StockMovementType, BigDecimal> sumByType(UUID productId);
}
