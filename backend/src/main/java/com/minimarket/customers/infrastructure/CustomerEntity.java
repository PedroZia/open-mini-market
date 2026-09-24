package com.minimarket.customers.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapeamento da tabela {@code customers} (§5.3 do plano). JPA explícito, sem Panache: o id (UUIDv7)
 * chega pronto do {@link CustomerRepository} e os valores são gravados como vieram — quem valida
 * forma é o caso de uso. A entidade não sai do módulo — nada de JPA em JSON.
 *
 * <p>Desativar é soft delete ({@code active = false} com {@code deleted_at}), como no produto: a
 * linha fica para o histórico das vendas e o índice único parcial libera o CPF. Cliente não tem
 * reativação, então não há caminho de volta para o desativado.
 */
@Entity
@Table(name = "customers")
public class CustomerEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "store_id")
  private UUID storeId;

  @Column(name = "name")
  private String name;

  /** CPF só com dígitos quando informado; nulo é o cliente sem documento. */
  @Column(name = "tax_id")
  private String taxId;

  @Column(name = "phone")
  private String phone;

  @Column(name = "email")
  private String email;

  @Column(name = "notes")
  private String notes;

  @Column(name = "active")
  private boolean active;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "version")
  private long version;

  /** Exigido pelo JPA. */
  protected CustomerEntity() {}

  /** Cliente novo nasce ativo, como no default da tabela; o id fica com o repositório. */
  public CustomerEntity(
      UUID storeId, String name, String taxId, String phone, String email, String notes) {
    this.storeId = storeId;
    this.name = name;
    this.taxId = taxId;
    this.phone = phone;
    this.email = email;
    this.notes = notes;
    this.active = true;
  }

  @PrePersist
  void markCreated() {
    Instant now = Instant.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void markUpdated() {
    updatedAt = Instant.now();
  }

  void assignId(UUID id) {
    this.id = id;
  }

  /** Campos que o 502b edita; o status só muda em {@link #markDisabled(Instant)}. */
  void updateDetails(String name, String taxId, String phone, String email, String notes) {
    this.name = name;
    this.taxId = taxId;
    this.phone = phone;
    this.email = email;
    this.notes = notes;
  }

  /**
   * Desativação (soft delete): sai da busca e do detalhe sem apagar o histórico e libera o CPF no
   * índice único parcial. Como não há reativação de cliente, o {@code active} e o {@code
   * deleted_at} caem juntos — não existe a janela de "inativo sem deleted_at" do produto.
   */
  void markDisabled(Instant deletedAt) {
    this.active = false;
    this.deletedAt = deletedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getStoreId() {
    return storeId;
  }

  public String getName() {
    return name;
  }

  public String getTaxId() {
    return taxId;
  }

  public String getPhone() {
    return phone;
  }

  public String getEmail() {
    return email;
  }

  public String getNotes() {
    return notes;
  }

  public boolean isActive() {
    return active;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public long getVersion() {
    return version;
  }
}
