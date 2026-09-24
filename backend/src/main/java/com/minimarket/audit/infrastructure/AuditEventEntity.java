package com.minimarket.audit.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Mapeamento da tabela {@code audit_events} (§5.3). JPA explícito, sem Panache; a entidade não sai
 * do módulo — o que atravessa a porta {@link com.minimarket.audit.application.AuditEventStore} é o
 * record {@code NewAuditEvent} e o que a consulta (passo 1001) devolve é o record {@code
 * AuditEventSummary} (§2.2).
 *
 * <p>Sem FK e sem associação de propósito (a migration explica): o log não bloqueia nem é bloqueado
 * pela operação de negócio, e os ids guardados são históricos. {@code cash_session_id} fica nula
 * até a Fase 6 — o recorder não inventa de onde tirá-la.
 */
@Entity
@Table(name = "audit_events")
public class AuditEventEntity {

  /** Identity do banco (exceção ao UUIDv7, §5.3): o log é sequencial e só cresce. */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  /**
   * Quem preenche é o banco ({@code default now()}), não a aplicação: a coluna fica fora do INSERT
   * e o instante do evento é o do PostgreSQL — nada de {@code Clock} de caso de uso aqui.
   */
  @Column(name = "occurred_at", insertable = false, updatable = false)
  private Instant occurredAt;

  @Column(name = "store_id")
  private UUID storeId;

  @Column(name = "actor_user_id")
  private UUID actorUserId;

  @Column(name = "actor_username")
  private String actorUsername;

  @Column(name = "auth_session_id")
  private UUID authSessionId;

  /** Nula até a Fase 6: a sessão de caixa nasce com o módulo {@code cash}. */
  @Column(name = "cash_session_id")
  private UUID cashSessionId;

  @Column(name = "cash_register_id")
  private UUID cashRegisterId;

  @Column(name = "action")
  private String action;

  @Column(name = "entity_type")
  private String entityType;

  @Column(name = "entity_id")
  private UUID entityId;

  /** {@code API}/{@code TUI}/{@code WEB}/{@code SYSTEM}; o check constraint da migration valida. */
  @Column(name = "source")
  private String source;

  @Column(name = "request_id")
  private String requestId;

  @Column(name = "reason")
  private String reason;

  /** jsonb de verdade: o Hibernate serializa o mapa com o Jackson da aplicação. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "details")
  private Map<String, Object> details;

  /** Coluna {@code inet}, como em {@code auth_sessions}: endereço sem máscara e sem DNS. */
  @JdbcTypeCode(SqlTypes.INET)
  @Column(name = "ip")
  private InetAddress ip;

  /** Exigido pelo JPA. */
  protected AuditEventEntity() {}

  /**
   * Evento novo com os campos que a aplicação decide; id e {@code occurred_at} ficam com o banco.
   */
  public AuditEventEntity(
      UUID storeId,
      UUID actorUserId,
      String actorUsername,
      UUID authSessionId,
      UUID cashRegisterId,
      String action,
      String entityType,
      UUID entityId,
      String source,
      String requestId,
      String reason,
      Map<String, Object> details,
      InetAddress ip) {
    this.storeId = storeId;
    this.actorUserId = actorUserId;
    this.actorUsername = actorUsername;
    this.authSessionId = authSessionId;
    this.cashRegisterId = cashRegisterId;
    this.action = action;
    this.entityType = entityType;
    this.entityId = entityId;
    this.source = source;
    this.requestId = requestId;
    this.reason = reason;
    this.details = details;
    this.ip = ip;
  }

  /** Identity do banco, visível à consulta de auditoria (passo 1001) para o desempate da ordem. */
  public Long getId() {
    return id;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public UUID getStoreId() {
    return storeId;
  }

  public UUID getActorUserId() {
    return actorUserId;
  }

  public String getActorUsername() {
    return actorUsername;
  }

  public UUID getAuthSessionId() {
    return authSessionId;
  }

  public UUID getCashSessionId() {
    return cashSessionId;
  }

  public UUID getCashRegisterId() {
    return cashRegisterId;
  }

  public String getAction() {
    return action;
  }

  public String getEntityType() {
    return entityType;
  }

  public UUID getEntityId() {
    return entityId;
  }

  public String getSource() {
    return source;
  }

  public String getRequestId() {
    return requestId;
  }

  public String getReason() {
    return reason;
  }

  public Map<String, Object> getDetails() {
    return details;
  }

  public InetAddress getIp() {
    return ip;
  }
}
