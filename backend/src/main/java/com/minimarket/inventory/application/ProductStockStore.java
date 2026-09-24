package com.minimarket.inventory.application;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência do saldo de estoque (passo 702); o adaptador JPA fica em {@code
 * inventory.infrastructure}. Só tipos de aplicação atravessam: entidade JPA nunca chega aqui.
 */
public interface ProductStockStore {

  /**
   * Saldo do par (loja, produto); vazio quando o produto nunca teve movimento — a linha é criada
   * sob demanda pelo {@link #insertIfAbsent} no primeiro movimento (passo 703).
   */
  Optional<ProductStockSummary> findByProduct(UUID storeId, UUID productId);

  /**
   * Saldo do par com lock pessimista de escrita na linha ({@code SELECT ... FOR UPDATE}): a
   * transação que chama segura o lock até o fim e outra transação que tente a mesma linha espera a
   * primeira soltar. É o que permite aplicar movimento de estoque sem corrida (passo 703, §8).
   * Vazio quando não existe linha de saldo para o par.
   */
  Optional<ProductStockSummary> lockByProduct(UUID storeId, UUID productId);

  /**
   * Cria a linha de saldo zerado do par (loja, produto) se ela ainda não existir — o primeiro
   * movimento do produto (passo 703) chama antes de travar. Idempotente: a linha já existente não é
   * tocada nem duplicada, mesmo com dois primeiros movimentos simultâneos no mesmo produto.
   */
  void insertIfAbsent(UUID storeId, UUID productId);

  /**
   * Grava o saldo novo do produto (o passo 703 aplica o delta sob o lock do {@link #lockByProduct})
   * e deixa o flush acontecer: {@code updated_at} é reposto pelo {@code @PreUpdate} e o {@code
   * version} avança — o {@code @Version} segue como backstop se algo escapar do lock.
   */
  void updateQuantity(UUID id, BigDecimal newQuantity);
}
