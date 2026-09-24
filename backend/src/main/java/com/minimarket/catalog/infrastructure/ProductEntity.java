package com.minimarket.catalog.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapeamento da tabela {@code products} (§5.3 do plano). JPA explícito, sem Panache: o id (UUIDv7)
 * chega pronto do {@link ProductRepository} e os valores são gravados como vieram — quem valida
 * forma é o caso de uso. A entidade não sai do módulo — nada de JPA em JSON.
 *
 * <p>Só as colunas usadas hoje: {@code sku} fica sem mapeamento até existir caso de uso que o leia.
 */
@Entity
@Table(name = "products")
public class ProductEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "store_id")
  private UUID storeId;

  @Column(name = "barcode")
  private String barcode;

  /**
   * PLU da etiqueta de balança (passos 1104b1/1104d, BR-14): o PUT de cadastro o substitui junto
   * com os demais campos — nulo limpa. Diferente do {@code barcode}, não é imutável.
   */
  @Column(name = "internal_code")
  private String internalCode;

  @Column(name = "name")
  private String name;

  @Column(name = "description")
  private String description;

  @Column(name = "category_id")
  private UUID categoryId;

  @Column(name = "unit")
  private String unit;

  @Column(name = "price")
  private BigDecimal price;

  /** Custo de compra; nulo enquanto nenhuma entrada informou custo (passo 706 escreve). */
  @Column(name = "cost_price")
  private BigDecimal costPrice;

  /** Ponto de reposição; nulo quando o produto não controla mínimo (passo 410 edita). */
  @Column(name = "min_quantity")
  private BigDecimal minQuantity;

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
  protected ProductEntity() {}

  /** Produto novo nasce ativo, como no default da tabela; o id fica com o repositório. */
  public ProductEntity(
      UUID storeId,
      String name,
      String barcode,
      String internalCode,
      String description,
      UUID categoryId,
      String unit,
      BigDecimal price,
      BigDecimal minQuantity) {
    this.storeId = storeId;
    this.name = name;
    this.barcode = barcode;
    this.internalCode = internalCode;
    this.description = description;
    this.categoryId = categoryId;
    this.unit = unit;
    this.price = price;
    this.minQuantity = minQuantity;
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

  /** Campos que o passo 410 edita; preço, barcode e status não mudam por aqui. */
  void updateDetails(
      String name,
      String internalCode,
      UUID categoryId,
      String unit,
      String description,
      BigDecimal minQuantity) {
    this.name = name;
    this.internalCode = internalCode;
    this.categoryId = categoryId;
    this.unit = unit;
    this.description = description;
    this.minQuantity = minQuantity;
  }

  /** Preço novo do passo 411; cadastro, barcode e status não mudam por aqui. */
  void updatePrice(BigDecimal price) {
    this.price = price;
  }

  /** Custo novo do passo 706; preço de venda, cadastro, barcode e status não mudam por aqui. */
  void updateCostPrice(BigDecimal costPrice) {
    this.costPrice = costPrice;
  }

  void markDeleted(Instant deletedAt) {
    this.deletedAt = deletedAt;
  }

  /**
   * Desativação do passo 412: sai do catálogo (busca, detalhe e bipe) sem apagar o histórico e
   * libera o barcode no índice único parcial. Diferente do {@link #markDeleted}, o {@code active}
   * cai junto — é o estado que o caso de uso de reativar vai encontrar.
   */
  void markDisabled(Instant deletedAt) {
    this.active = false;
    markDeleted(deletedAt);
  }

  /** Reativação do passo 412: volta ao catálogo com o {@code deleted_at} limpo. */
  void markEnabled() {
    this.active = true;
    this.deletedAt = null;
  }

  public UUID getId() {
    return id;
  }

  public UUID getStoreId() {
    return storeId;
  }

  public String getBarcode() {
    return barcode;
  }

  public String getInternalCode() {
    return internalCode;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public UUID getCategoryId() {
    return categoryId;
  }

  public String getUnit() {
    return unit;
  }

  public BigDecimal getPrice() {
    return price;
  }

  public BigDecimal getMinQuantity() {
    return minQuantity;
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
