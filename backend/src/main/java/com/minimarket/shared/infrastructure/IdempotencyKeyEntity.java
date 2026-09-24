package com.minimarket.shared.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Mapeamento da tabela {@code idempotency_keys} (§5.3, passo 607a). JPA explícito, sem Panache; a
 * entidade não sai do módulo — o que atravessa a porta é {@link
 * com.minimarket.shared.application.StoredIdempotentResponse}.
 *
 * <p>A PK é a própria chave ({@code text}, sem surrogate): o INSERT com chave repetida é a corrida
 * do vencedor e sobe como violação de unique para o adaptador traduzir. {@code response_body} é
 * jsonb de verdade, guardado como o JSON já serializado (String tratada como documento cru pelo
 * mapeador, como em {@code audit_events.details}); o {@code created_at} fica com o default do
 * banco.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKeyEntity {

  @Id
  @Column(name = "key")
  private String key;

  @Column(name = "user_id")
  private UUID userId;

  @Column(name = "method")
  private String method;

  @Column(name = "path")
  private String path;

  @Column(name = "request_hash")
  private String requestHash;

  @Column(name = "status_code")
  private int statusCode;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "response_body")
  private String responseBody;

  /** Quem preenche é o banco ({@code default now()}); a coluna fica fora do INSERT. */
  @Column(name = "created_at", insertable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "expires_at")
  private Instant expiresAt;

  /** Exigido pelo JPA. */
  protected IdempotencyKeyEntity() {}

  /** Registro novo: a aplicação decide a expiração e o banco completa o {@code created_at}. */
  public IdempotencyKeyEntity(
      String key,
      UUID userId,
      String method,
      String path,
      String requestHash,
      int statusCode,
      String responseBody,
      Instant expiresAt) {
    this.key = key;
    this.userId = userId;
    this.method = method;
    this.path = path;
    this.requestHash = requestHash;
    this.statusCode = statusCode;
    this.responseBody = responseBody;
    this.expiresAt = expiresAt;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getMethod() {
    return method;
  }

  public String getPath() {
    return path;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public int getStatusCode() {
    return statusCode;
  }

  public String getResponseBody() {
    return responseBody;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }
}
