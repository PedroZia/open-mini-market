package com.minimarket.audit.application;

import com.minimarket.shared.domain.OperationSource;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Evento de auditoria projetado para leitura (passo 1001): é o que a porta {@link
 * AuditEventQueryStore} devolve e a API mapeia para o DTO. A entidade JPA não sai de {@code
 * audit.infrastructure} (§2.2) — a projeção carrega só os campos do contrato.
 *
 * <p>{@code id} é o identity do banco (exceção ao UUIDv7, §5.3) e {@code occurredAt} é o instante
 * do PostgreSQL ({@code default now()}): a linha do tempo é a ordem de gravação do log. {@code
 * cashSessionId} segue nulo até o recorder passar a preenchê-lo (Fase 6+); {@code ip} chega aqui já
 * como texto de {@code InetAddress.getHostAddress()} — {@code InetAddress} cru não é serializado.
 */
public record AuditEventSummary(
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
