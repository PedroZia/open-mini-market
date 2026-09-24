package com.minimarket.audit.api;

import com.minimarket.shared.domain.OperationSource;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Evento de auditoria no contrato de leitura (passo 1001, §7.3): todos os campos de {@code
 * audit_events} menos os que ficam internos — a projeção {@link
 * com.minimarket.audit.application.AuditEventSummary} é mapeada para cá e a entidade JPA nunca vai
 * a JSON. {@code ip} é o texto de {@code InetAddress.getHostAddress()} e {@code source} é a origem
 * ({@code API}/{@code TUI}/{@code WEB}/{@code SYSTEM}, o mesmo check da coluna).
 */
public record AuditEventResponse(
    long id,
    Instant occurredAt,
    UUID storeId,
    UUID actorUserId,
    String actorUsername,
    UUID authSessionId,
    UUID cashSessionId,
    UUID cashRegisterId,
    String action,
    String entityType,
    UUID entityId,
    OperationSource source,
    String requestId,
    String reason,
    Map<String, Object> details,
    String ip) {}
