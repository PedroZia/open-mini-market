package com.minimarket.users.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Mapeamento da tabela {@code users} (§5.3 do plano). JPA explícito, sem Panache: o id (UUIDv7) e o
 * username normalizado chegam prontos do {@link UserRepository}; regra de negócio mora nos casos de
 * uso. A entidade não sai do módulo — nada de JPA em JSON.
 *
 * <p>Só as colunas usadas hoje: {@code failed_login_attempts}, {@code locked_until} e {@code
 * last_login_at} entram quando o caso de uso que as usa existir.
 */
@Entity
@Table(name = "users")
public class UserEntity {

  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_DISABLED = "DISABLED";

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "username")
  private String username;

  @Column(name = "password_hash")
  private String passwordHash;

  @Column(name = "display_name")
  private String displayName;

  @Column(name = "status")
  private String status;

  /** Senha temporária definida por ADMIN (passo 113) exige troca no próximo login. */
  @Column(name = "must_change_password")
  private boolean mustChangePassword;

  @Column(name = "password_changed_at")
  private Instant passwordChangedAt;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "version")
  private long version;

  /**
   * Lado dono de {@code user_roles}: as colunas extras da tabela de junção ({@code granted_at}, com
   * default no banco, e {@code granted_by_user_id}, nulo) ficam sem mapeamento até existir caso de
   * uso que as leia.
   */
  @ManyToMany
  @JoinTable(
      name = "user_roles",
      joinColumns = @JoinColumn(name = "user_id"),
      inverseJoinColumns = @JoinColumn(name = "role_id"))
  private Set<RoleEntity> roles = new LinkedHashSet<>();

  /** Exigido pelo JPA. */
  protected UserEntity() {}

  /** Usuário novo nasce {@code ACTIVE}; id e normalização do username ficam com o repositório. */
  public UserEntity(String username, String passwordHash, String displayName) {
    this.username = username;
    this.passwordHash = passwordHash;
    this.displayName = displayName;
    this.status = STATUS_ACTIVE;
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

  void assignUsername(String username) {
    this.username = username;
  }

  void markDeleted(Instant deletedAt) {
    this.deletedAt = deletedAt;
  }

  /** Reativação (passo 112): limpa o soft delete; o status é decisão do repositório. */
  void markRestored() {
    this.deletedAt = null;
  }

  /**
   * Reset de senha por ADMIN (passo 113): troca o hash, exige a troca no próximo login e registra
   * quando a senha mudou.
   */
  void resetPassword(String passwordHash) {
    this.passwordHash = passwordHash;
    this.mustChangePassword = true;
    this.passwordChangedAt = Instant.now();
  }

  /**
   * ADMIN inicial (passo 115): exige a troca da senha no primeiro login sem mexer no hash, que já
   * foi definido na criação do usuário.
   */
  void requirePasswordChange() {
    this.mustChangePassword = true;
  }

  public UUID getId() {
    return id;
  }

  public String getUsername() {
    return username;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  /** Indica se o usuário precisa trocar a senha no próximo login. */
  public boolean isMustChangePassword() {
    return mustChangePassword;
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

  /** Roles atuais do usuário; alterar a coleção é o que a troca de papéis faz. */
  public Set<RoleEntity> getRoles() {
    return roles;
  }
}
