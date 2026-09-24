package com.minimarket.audit.application;

import com.minimarket.shared.domain.OperationSource;
import java.net.InetAddress;
import java.util.Map;
import java.util.UUID;

/**
 * Evento pronto para gravar, montado pelo {@link AuditRecorder} (passo 303): é o que a tabela
 * {@code audit_events} guarda, menos o que só o banco decide — o {@code id} (identity) e o {@code
 * occurred_at} ({@code default now()}). {@code cashSessionId} (passo 1006) é a sessão de caixa da
 * operação: quem a conhece a passa explícita ao recorder; as ações que não têm sessão (usuários,
 * auth, estoque manual) gravam nulo.
 *
 * <p>Record de aplicação: nem a entidade JPA nem o {@code EntityManager} atravessam a porta {@link
 * AuditEventStore} (§2.2).
 */
public record NewAuditEvent(
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
    InetAddress ip) {}
