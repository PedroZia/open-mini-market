package com.minimarket.cash.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Mapeamento da tabela {@code cash_registers} (§5.3 do plano). Só as colunas usadas hoje — a
 * listagem lê a tabela, nunca escreve — e a entidade não sai do módulo: nada de JPA em JSON.
 */
@Entity
@Table(name = "cash_registers")
public class CashRegisterEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "code")
  private String code;

  @Column(name = "name")
  private String name;

  @Column(name = "active")
  private boolean active;

  /** Exigido pelo JPA. */
  protected CashRegisterEntity() {}

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }
}
