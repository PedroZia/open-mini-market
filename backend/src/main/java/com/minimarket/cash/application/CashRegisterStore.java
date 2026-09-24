package com.minimarket.cash.application;

import java.util.List;

/**
 * Porta de persistência do caixa físico; o adaptador JPA fica em {@code cash.infrastructure}. Só
 * tipos de aplicação atravessam: entidade JPA nunca chega aqui.
 *
 * <p>Só a leitura da listagem (passo 602) por enquanto: a criação/edição de caixa não existe no MVP
 * — o caixa nasce no seed da V11.
 */
public interface CashRegisterStore {

  /** Caixas ativos ordenados por código — é a lista que a TUI mostra no login (§9.3). */
  List<CashRegisterSummary> listActive();
}
