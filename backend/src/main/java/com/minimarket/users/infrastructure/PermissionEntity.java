package com.minimarket.users.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Mapeamento da tabela {@code permissions} (§5.3 do plano), catálogo criado por migration e
 * imutável em runtime. JPA explícito, sem Panache: só as colunas usadas hoje (id e code) e a
 * entidade não sai do módulo — nada de JPA em JSON.
 */
@Entity
@Table(name = "permissions")
public class PermissionEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "code")
  private String code;

  /** Exigido pelo JPA. */
  protected PermissionEntity() {}

  public String getCode() {
    return code;
  }
}
