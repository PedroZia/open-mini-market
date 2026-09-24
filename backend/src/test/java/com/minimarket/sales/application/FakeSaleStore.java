package com.minimarket.sales.application;

import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Dublê de {@link SaleStore} dos unitários das operações de item (passos 808/809): guarda a venda
 * do cenário e o que o caso de uso mandou atualizar. Leitura, busca, contagem e lock são
 * exercitados pelos testes de integração do passo 803 — aqui o que importa é o que o caso de uso
 * fez com o agregado e se gravou.
 */
final class FakeSaleStore implements SaleStore {

  /** Venda do cenário; nula é o cenário da venda inexistente. */
  Sale sale;

  /** Agregado que o caso de uso entregou ao {@code update}; nulo quando não houve gravação. */
  Sale updated;

  /**
   * Quantas vezes o caso de uso pediu gravação; zero é o cenário em que a operação não acontece.
   */
  int updateCount;

  @Override
  public Optional<Sale> findById(UUID id) {
    return Optional.ofNullable(sale).filter(found -> found.id().equals(id));
  }

  @Override
  public void update(Sale sale) {
    updateCount++;
    updated = sale;
  }

  @Override
  public void insert(Sale sale) {
    throw new UnsupportedOperationException("insert não é usado pelas operações de item");
  }

  @Override
  public List<SaleSummary> search(
      Instant from,
      Instant to,
      SaleStatus status,
      UUID cashSessionId,
      UUID operatorUserId,
      int page,
      int size) {
    throw new UnsupportedOperationException("search não é usado pelas operações de item");
  }

  @Override
  public long count(
      Instant from, Instant to, SaleStatus status, UUID cashSessionId, UUID operatorUserId) {
    throw new UnsupportedOperationException("count não é usado pelas operações de item");
  }

  @Override
  public Optional<Sale> lockById(UUID id) {
    throw new UnsupportedOperationException("lockById não é usado pelas operações de item");
  }

  @Override
  public boolean existsOpenByCashSession(UUID cashSessionId) {
    throw new UnsupportedOperationException(
        "existsOpenByCashSession não é usado pelas operações de item");
  }
}
