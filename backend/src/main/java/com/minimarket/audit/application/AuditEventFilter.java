package com.minimarket.audit.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Filtros da consulta de auditoria (passo 1001, §7.3 do plano): os do §9.3 — {@code entityType},
 * {@code entityId}, {@code actorUserId}, {@code action}, {@code cashSessionId} e período — todos
 * opcionais e combináveis. Nulo é "sem filtro"; texto em branco é tratado como nulo pelo adaptador,
 * o mesmo critério da busca de produtos.
 *
 * <p>O período é sobre {@code occurred_at} e segue o §9.1: {@code from} inclusivo e {@code to}
 * exclusivo. Record de aplicação: a string do cliente não atravessa a porta {@link
 * AuditEventQueryStore} — a whitelist de ordenação é resolvida antes, pelo {@link
 * ListAuditEventsUseCase}.
 */
public record AuditEventFilter(
    String entityType,
    UUID entityId,
    UUID actorUserId,
    String action,
    UUID cashSessionId,
    Instant from,
    Instant to) {}
