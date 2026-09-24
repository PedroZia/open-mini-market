package com.minimarket.cash.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência do caixa físico; o adaptador JPA fica em {@code cash.infrastructure}. Só
 * tipos de aplicação atravessam: entidade JPA nunca chega aqui.
 *
 * <p>Só a leitura por enquanto (passos 602 e 606): a criação/edição de caixa não existe no MVP — o
 * caixa nasce no seed da V11.
 */
public interface CashRegisterStore {

  /** Caixas ativos ordenados por código — é a lista que a TUI mostra no login (§9.3). */
  List<CashRegisterSummary> listActive();

  /**
   * Caixa ativo pelo id (passo 606): é a checagem da abertura. Caixa inexistente <em>ou</em>
   * inativo devolve vazio — quem chama responde o mesmo 404 {@code CASH_REGISTER_NOT_FOUND} para os
   * dois casos, sem revelar a existência do caixa desativado.
   */
  Optional<CashRegisterSummary> findActiveById(UUID id);
}
