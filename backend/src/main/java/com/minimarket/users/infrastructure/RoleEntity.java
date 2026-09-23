package com.minimarket.users.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Mapeamento da tabela {@code roles} (§5.3 do plano). JPA explícito, sem Panache: só as colunas
 * usadas hoje (id e code) — name/description/system entram quando houver caso de uso que os leia —
 * e a entidade não sai do módulo: nada de JPA em JSON.
 *
 * <p>O lado dono de {@code role_permissions} é esta coleção: {@code replacePermissions} troca o
 * conjunto e o Hibernate cuida das linhas da tabela de junção.
 */
@Entity
@Table(name = "roles")
public class RoleEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "code")
  private String code;

  @ManyToMany
  @JoinTable(
      name = "role_permissions",
      joinColumns = @JoinColumn(name = "role_id"),
      inverseJoinColumns = @JoinColumn(name = "permission_id"))
  private Set<PermissionEntity> permissions = new LinkedHashSet<>();

  /** Exigido pelo JPA. */
  protected RoleEntity() {}

  public String getCode() {
    return code;
  }

  /** Permissões atuais da role; alterar a coleção é o que a troca de permissões faz. */
  public Set<PermissionEntity> getPermissions() {
    return permissions;
  }
}
