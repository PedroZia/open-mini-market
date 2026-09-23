package com.minimarket.users.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
   * Troca o nome de exibição do usuário vivo; não mexe em username, senha, status nem papéis. Se o
   * id não existe (ou está soft-deletado) nada é gravado — a existência é validada pelo caso de uso
   * antes da chamada.
   */
  void updateDisplayName(UUID id, String displayName);

  /**
   * Usuário vivo pelo id, com roles e status. Soft-deletado devolve vazio — o filtro de {@code
   * deleted_at} mora no adaptador, como em {@link #search}; usuário apenas {@code DISABLED}
   * continua sendo devolvido.
   */
  Optional<UserSummary> findSummaryById(UUID id);

  /**
   * Desativa o usuário vivo (passo 112): grava {@code status = DISABLED} e {@code deleted_at} e
   * devolve a projeção já atualizada. Vazio quando não existe usuário vivo com o id — quem já está
   * desativado cai aqui, e o 404 é do caso de uso.
   */
  Optional<UserSummary> disable(UUID id);

  /**
   * Reativa o usuário (passo 112): grava {@code status = ACTIVE}, limpa {@code deleted_at} e
   * devolve a projeção já atualizada. Diferente de {@link #findSummaryById}, enxerga o
   * soft-deletado — é o registro que a reativação precisa alcançar. Usuário já ativo é no-op
   * (mesmos valores, sem update) e id inexistente devolve vazio.
   */
  Optional<UserSummary> enable(UUID id);

  /**
   * Reset de senha por ADMIN (passo 113): grava o hash da senha temporária, marca {@code
   * must_change_password} e atualiza {@code password_changed_at}, devolvendo a projeção já
   * atualizada. Soft-deletado devolve vazio — o 404 é do caso de uso.
   */
  Optional<UserSummary> resetPassword(UUID id, String passwordHash);

  /**
   * ADMIN inicial (passo 115): exige a troca da senha no primeiro login sem tocar no hash, porque a
   * senha já foi definida na criação. Soft-deletado não é marcado — mesmo filtro de {@link
   * #updateDisplayName}.
   */
  void requirePasswordChange(UUID id);

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

  /**
   * Estado de autenticação do usuário pelo username (já normalizado), para o login (passo 204a).
   * Diferente de {@link #findSummaryById}, enxerga o usuário soft-deletado: é o caso de uso que
   * decide recusar — e o faz com a mensagem genérica de credenciais inválidas. Vazio quando não
   * existe usuário com o username.
   */
  Optional<UserAuthState> findAuthStateByUsername(String username);

  /**
   * Sucesso do login (passos 204a/204b): grava {@code last_login_at} e zera o contador de falhas e
   * o lock. Usuário soft-deletado é no-op, como em {@link #updateDisplayName}; o instante vem do
   * {@code Clock} do caso de uso.
   */
  void recordSuccessfulLogin(UUID id, Instant loginAt);

  /**
   * Falha de login (passo 204b): grava o contador já incrementado e, quando a política fecha o
   * ciclo, o instante até o qual o login fica bloqueado ({@code lockedUntil} nulo enquanto não há
   * bloqueio). O valor do contador e do lock é decisão do caso de uso — aqui só persiste. Usuário
   * soft-deletado é no-op, como em {@link #updateDisplayName}.
   */
  void recordFailedLogin(UUID id, int failedLoginAttempts, Instant lockedUntil);

  /**
   * Desbloqueio por expiração (passo 204b): zera o contador e {@code locked_until} para a tentativa
   * recomeçar do zero. Usuário soft-deletado é no-op, como em {@link #updateDisplayName}.
   */
  void clearLoginFailures(UUID id);

  /**
   * Rehash do login (passo 204a): troca o hash quando a política de senha ficou mais forte, sem
   * exigir troca nem mexer em {@code password_changed_at} — a senha é a mesma. Soft-deletado é
   * no-op, como em {@link #updateDisplayName}.
   */
  void updatePasswordHash(UUID id, String passwordHash);
}
