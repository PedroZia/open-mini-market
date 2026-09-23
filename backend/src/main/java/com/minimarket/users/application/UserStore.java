package com.minimarket.users.application;

import java.util.UUID;

/**
 * Porta de persistência do usuário; o adaptador JPA fica em {@code users.infrastructure}. Só tipos
 * simples atravessam: nem entidade nem JPA chegam a {@code application}.
 */
public interface UserStore {

  /**
   * Indica se existe usuário vivo com o username informado (já normalizado). Soft-deletado não
   * conta: o username volta a ficar livre (§5.3).
   */
  boolean existsByUsername(String username);

  /** Insere o usuário e devolve o id gerado (UUIDv7) pelo adaptador. */
  UUID insert(NewUser user);
}
