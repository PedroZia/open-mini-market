package com.minimarket.users.application;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Porta de atribuição de papéis; o adaptador JPA fica em {@code users.infrastructure}. */
public interface RoleStore {

  /**
   * Substitui os papéis do usuário pelos códigos informados. Usuário ou código inexistente derruba
   * a operação com {@code NotFoundException} — regra do adaptador, sem gravar nada.
   */
  void assignRoles(UUID userId, Collection<String> roleCodes);

  /**
   * Códigos informados que não existem no catálogo de papéis, na ordem de entrada. Consulta pura,
   * sem gravar nada: quem transforma o resultado em erro (400 {@code UNKNOWN_ROLE}) é o caso de
   * uso, antes de tocar no usuário — o 400 não sai de exceção do adaptador.
   */
  Set<String> findUnknownCodes(Collection<String> roleCodes);

  /**
   * Quantos usuários vivos ({@code deleted_at} nulo), com {@code status = ACTIVE} e com o papel
   * informado existem hoje. É o insumo da regra "não desativar o último ADMIN ativo" (passo 112);
   * consulta pura, sem gravar nada.
   */
  long countActiveUsersWithRole(String roleCode);

  /**
   * Ids dos usuários vivos ({@code deleted_at} nulo), com {@code status = ACTIVE} e com o papel
   * informado, travados para escrita ({@code select ... for update} nas linhas de {@code users})
   * até o fim da transação do caso de uso. É o insumo da regra "não desativar o último ADMIN ativo"
   * sob concorrência (passo 1008): dois disables simultâneos disputam o mesmo lock e o segundo
   * reavalia a lista já sem o ADMIN que o vencedor desativou — o lock morre com a transação. Lista
   * vazia quando não há ninguém.
   */
  List<UUID> lockActiveUserIdsWithRole(String roleCode);
}
