package com.minimarket.audit.application;

import java.util.List;

/** Página da consulta de auditoria já validada e limitada pelo {@link ListAuditEventsUseCase}. */
public record AuditEventPage(
    List<AuditEventSummary> items, int page, int size, long totalItems, int totalPages) {}
