package com.minimarket.audit.application;

import com.minimarket.shared.domain.OperationSource;
import java.net.InetAddress;
import java.util.Map;
import java.util.UUID;

/**
 * Evento pronto para gravar, montado pelo {@link AuditRecorder} (passo 303): é o que a tabela
 * {@code audit_events} guarda, menos o que só o banco decide — o {@code id} (identity) e o {@code
 * occurred_at} ({@code default now()}). {@code cash_session_id} nem aparece: a sessão de caixa só
 * nasce na Fase 6 e a coluna fica nula até lá.
 *
 * <p>Record de aplicação: nem a entidade JPA nem o {@code EntityManager} atravessam a porta {@link
 * AuditEventStore} (§2.2).
 */
public record NewAuditEvent(
    UUID storeId,
    UUID actorUserId,
    String actorUsername,
    UUID authSessionId,
    UUID cashRegisterId,
    String action,
    String entityType,
    UUID entityId,
    OperationSource source,
    String requestId,
    String reason,
    Map<String, Object> details,
    InetAddress ip) {}
