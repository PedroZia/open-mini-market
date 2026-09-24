package com.minimarket.shared.application;

import java.util.UUID;

/**
 * Porta de leitura do caixa para quem não pode depender de {@code cash.application} (inversão do
 * passo 607b): o login, em {@code auth.application}, valida o caixa informado sem criar o ciclo
 * {@code auth → cash} — {@code cash} já depende de {@code auth} desde o passo 606. O adaptador é o
 * repositório de caixa, em {@code cash.infrastructure}, que também implementa {@code
 * CashRegisterStore}.
 */
public interface CashRegisterLookup {

  /** Caixa existente e ativo? Caixa inexistente ou inativo devolve {@code false}. */
  boolean isActive(UUID id);
}
