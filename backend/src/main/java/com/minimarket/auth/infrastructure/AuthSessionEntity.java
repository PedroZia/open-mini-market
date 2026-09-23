package com.minimarket.auth.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Mapeamento da tabela {@code auth_sessions} (§5.3 do plano). JPA explícito, sem Panache: o id
 * (UUIDv7) chega pronto do {@link AuthSessionRepository}, o token nunca aparece aqui (só o hash,
 * §6.2) e a entidade não sai do módulo — nada de JPA em JSON.
 *
 * <p>{@code user_id} e {@code store_id} são colunas de uuid simples, não associações: entidade JPA
 * de outro módulo não atravessa fronteira, e as FKs do banco continuam garantindo a integridade.
 */
@Entity
@Table(name = "auth_sessions")
public class AuthSessionEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "user_id")
  private UUID userId;

  @Column(name = "token_hash")
  private String tokenHash;

  /** {@code TUI} ou {@code WEB}; o check constraint da migration é quem valida o valor. */
  @Column(name = "client")
  private String client;

  @Column(name = "store_id")
  private UUID storeId;

  /** Sem FK até a Fase 6, quando a tabela de caixa nascer. */
  @Column(name = "cash_register_id")
  private UUID cashRegisterId;

  /**
   * Coluna {@code inet} de verdade: {@link InetAddress} é o tipo que o Hibernate recomenda para
   * {@code SqlTypes.INET} e o dialeto do PostgreSQL grava e lê pelo {@code PGobject} do driver.
   */
  @JdbcTypeCode(SqlTypes.INET)
  @Column(name = "ip")
  private InetAddress ip;

  @Column(name = "user_agent")
  private String userAgent;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "last_seen_at")
  private Instant lastSeenAt;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "revoked_reason")
  private String revokedReason;

  @Version
  @Column(name = "version")
  private long version;

  /** Exigido pelo JPA. */
  protected AuthSessionEntity() {}

  /**
   * Sessão nova; o id (UUIDv7) fica com o repositório e {@code created_at} com o ciclo de vida do
   * JPA. {@code lastSeenAt} (marco zero do idle timeout) e {@code expiresAt} vêm do relógio do caso
   * de uso — o login injeta o {@code Clock} e os testes de expiração precisam dele.
   */
  public AuthSessionEntity(
      UUID userId,
      String tokenHash,
      String client,
      UUID storeId,
      UUID cashRegisterId,
      InetAddress ip,
      String userAgent,
      Instant lastSeenAt,
      Instant expiresAt) {
    this.userId = userId;
    this.tokenHash = tokenHash;
    this.client = client;
    this.storeId = storeId;
    this.cashRegisterId = cashRegisterId;
    this.ip = ip;
    this.userAgent = userAgent;
    this.lastSeenAt = lastSeenAt;
    this.expiresAt = expiresAt;
  }

  @PrePersist
  void markCreated() {
    createdAt = Instant.now();
  }

  void assignId(UUID id) {
    this.id = id;
  }

  /** Atualização de atividade do idle timeout (§6.2); o instante é decisão do caso de uso. */
  void markSeen(Instant seenAt) {
    this.lastSeenAt = seenAt;
  }

  /** Revogação idempotente: sessão já revogada mantém instante e motivo originais. */
  void revoke(String reason, Instant revokedAt) {
    if (this.revokedAt != null) {
      return;
    }
    this.revokedAt = revokedAt;
    this.revokedReason = reason;
  }

  /** Sessão ativa é a não revogada; expiração é decisão do caso de uso (§6.2), não da entidade. */
  public boolean isActive() {
    return revokedAt == null;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public String getClient() {
    return client;
  }

  public UUID getStoreId() {
    return storeId;
  }

  public UUID getCashRegisterId() {
    return cashRegisterId;
  }

  public InetAddress getIp() {
    return ip;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getLastSeenAt() {
    return lastSeenAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public String getRevokedReason() {
    return revokedReason;
  }

  public long getVersion() {
    return version;
  }
}
