package com.minimarket.catalog.infrastructure;

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
 * Mapeamento da tabela {@code categories} (§5.3 do plano). JPA explícito, sem Panache: o id
 * (UUIDv7) chega pronto do {@link CategoryRepository} e o nome é gravado como veio — quem valida
 * forma é o caso de uso. A entidade não sai do módulo — nada de JPA em JSON.
 *
 * <p>Sem {@code deleted_at}, diferente de {@code users}: categoria desativada continua na tabela
 * ({@code active = false}) para não quebrar produtos e vendas que a referenciam.
 */
@Entity
@Table(name = "categories")
public class CategoryEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "store_id")
  private UUID storeId;

  @Column(name = "name")
  private String name;

  @Column(name = "parent_id")
  private UUID parentId;

  @Column(name = "active")
  private boolean active;

  @Column(name = "sort_order")
  private int sortOrder;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @Version
  @Column(name = "version")
  private long version;

  /** Exigido pelo JPA. */
  protected CategoryEntity() {}

  /** Categoria nova nasce ativa; o id fica com o repositório. */
  public CategoryEntity(UUID storeId, String name, UUID parentId, int sortOrder) {
    this.storeId = storeId;
    this.name = name;
    this.parentId = parentId;
    this.sortOrder = sortOrder;
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

  /** Nome, hierarquia e ordenação; o estado ativo só muda em {@link #deactivate()}. */
  void updateDetails(String name, UUID parentId, int sortOrder) {
    this.name = name;
    this.parentId = parentId;
    this.sortOrder = sortOrder;
  }

  /** Desativa sem apagar a linha. */
  void deactivate() {
    this.active = false;
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

  public UUID getParentId() {
    return parentId;
  }

  public boolean isActive() {
    return active;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }
}
