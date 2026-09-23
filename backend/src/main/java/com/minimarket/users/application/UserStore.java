package com.minimarket.users.application;

import java.util.List;
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

  /**
   * Página de usuários vivos (soft-deletado nunca aparece) com filtro textual em
   * username/display_name sem diferenciar maiúsculas ({@code search} em branco = sem filtro) e
   * filtro de status ({@code active} nulo = todos). {@code sort} é a whitelist já resolvida pelo
   * caso de uso — string do cliente nunca chega à consulta.
   */
  List<UserSummary> search(
      String search, Boolean active, UserSort sort, boolean ascending, int page, int size);

  /**
   * Total de usuários vivos que casam com {@code search}/{@code active}, para o {@code totalItems}
   * e o {@code totalPages} da página.
   */
  long count(String search, Boolean active);
}
