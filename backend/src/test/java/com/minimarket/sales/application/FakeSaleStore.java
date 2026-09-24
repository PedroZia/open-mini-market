package com.minimarket.sales.application;

import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Dublê de {@link SaleStore} dos unitários das operações de item (passos 808/809) e da consulta
 * (passo 812): guarda a venda do cenário e o que o caso de uso mandou atualizar, e devolve o
 * resultado configurado da busca/contagem registrando como o caso de uso as chamou. O round-trip
 * real (find/insert/update/search/lock) é exercitado pelos testes de integração do passo 803 — aqui
 * o que importa é o que o caso de uso fez com o agregado e o que repassou à porta.
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

  /** Resultado que a busca devolve; vazio é a página sem venda. */
  List<SaleSummary> searchResult = List.of();

  /** Total que a contagem devolve — o {@code totalItems} da página montada pelo caso de uso. */
  long countResult;

  /** Como o caso de uso chamou a busca; nula quando não houve busca. */
  SearchCall searchCall;

  /** Como o caso de uso chamou a contagem; nula quando não houve contagem. */
  CountCall countCall;

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
    throw new UnsupportedOperationException("insert não é usado pela consulta");
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
    searchCall = new SearchCall(from, to, status, cashSessionId, operatorUserId, page, size);
    return searchResult;
  }

  @Override
  public long count(
      Instant from, Instant to, SaleStatus status, UUID cashSessionId, UUID operatorUserId) {
    countCall = new CountCall(from, to, status, cashSessionId, operatorUserId);
    return countResult;
  }

  @Override
  public Optional<Sale> lockById(UUID id) {
    throw new UnsupportedOperationException("lockById não é usado pela consulta");
  }

  @Override
  public boolean existsOpenByCashSession(UUID cashSessionId) {
    throw new UnsupportedOperationException("existsOpenByCashSession não é usado pela consulta");
  }

  /** Argumentos de uma busca, como o caso de uso os repassou. */
  record SearchCall(
      Instant from,
      Instant to,
      SaleStatus status,
      UUID cashSessionId,
      UUID operatorUserId,
      int page,
      int size) {}

  /** Argumentos de uma contagem, como o caso de uso os repassou. */
  record CountCall(
      Instant from, Instant to, SaleStatus status, UUID cashSessionId, UUID operatorUserId) {}
}
