package com.minimarket.audit.api;

import com.minimarket.audit.application.AuditEventFilter;
import com.minimarket.audit.application.AuditEventPage;
import com.minimarket.audit.application.AuditEventSummary;
import com.minimarket.audit.application.ListAuditEventsUseCase;
import com.minimarket.shared.api.PageResponse;
import com.minimarket.shared.api.RequirePermission;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.Permission;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Consulta do log de auditoria (passo 1001, §7.3 e §9.3). A API valida a forma, delega ao caso de
 * uso e mapeia a resposta — zero regra de negócio aqui.
 *
 * <p>Exige {@code audit.read} (§4.5): sem a permissão o interceptor do {@code RequirePermission}
 * responde 403 {@code ACCESS_DENIED} antes de o corpo do método rodar — é o 403 do OPERADOR. Os
 * filtros são os do §9.3, todos opcionais e combináveis: {@code entityType}, {@code entityId},
 * {@code actorUserId}, {@code action}, {@code cashSessionId} e o período sobre {@code occurred_at}
 * ({@code from} inclusivo, {@code to} exclusivo, §9.1).
 *
 * <p>{@code from}/{@code to} chegam como texto e são convertidos aqui, de propósito: o conversor
 * implícito do JAX-RS devolveria 404 para um valor inválido, e a convenção da API é 400 {@code
 * VALIDATION_ERROR}. A ordenação default é {@code occurredat,desc} — a linha do tempo investigada
 * vem do mais recente para o mais antigo.
 */
@Path(AuditEventsResource.PATH)
public class AuditEventsResource {

  /** Caminho do recurso (§9.3); o path carrega a versão da API (§9.1). */
  public static final String PATH = "/api/v1/audit-events";

  @Inject ListAuditEventsUseCase listAuditEventsUseCase;

  /**
   * Lista paginada com os filtros do §9.3. {@code sort} aceita {@code occurredat} com {@code
   * ,asc|desc} opcional (default {@code occurredat,desc}) e {@code size} acima de 100 é limitado;
   * {@code page} negativo, {@code size} menor que 1, {@code sort} fora da whitelist ou data/período
   * em formato inválido → 400 {@code VALIDATION_ERROR}.
   */
  @GET
  @RequirePermission(Permission.AUDIT_READ)
  @Produces(MediaType.APPLICATION_JSON)
  public PageResponse<AuditEventResponse> list(
      @QueryParam("entityType") String entityType,
      @QueryParam("entityId") UUID entityId,
      @QueryParam("actorUserId") UUID actorUserId,
      @QueryParam("action") String action,
      @QueryParam("cashSessionId") UUID cashSessionId,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("sort") String sort,
      @QueryParam("page") @DefaultValue("0") int page,
      @QueryParam("size") @DefaultValue("20") int size) {
    AuditEventPage events =
        listAuditEventsUseCase.execute(
            new AuditEventFilter(
                entityType,
                entityId,
                actorUserId,
                action,
                cashSessionId,
                instantOf(from, "from"),
                instantOf(to, "to")),
            sort,
            page,
            size);
    return new PageResponse<>(
        events.items().stream().map(AuditEventsResource::toResponse).toList(),
        events.page(),
        events.size(),
        events.totalItems(),
        events.totalPages());
  }

  private static AuditEventResponse toResponse(AuditEventSummary event) {
    return new AuditEventResponse(
        event.id(),
        event.occurredAt(),
        event.storeId(),
        event.actorUserId(),
        event.actorUsername(),
        event.authSessionId(),
        event.cashSessionId(),
        event.cashRegisterId(),
        event.action(),
        event.entityType(),
        event.entityId(),
        event.source(),
        event.requestId(),
        event.reason(),
        event.details(),
        event.ip());
  }

  /**
   * Instante do filtro de período. Em branco (parâmetro ausente ou {@code from=}) é "sem filtro";
   * texto que não é ISO-8601 com offset — ex.: {@code 2026-02-01}, sem hora e fuso — → 400 {@code
   * VALIDATION_ERROR}. O instante é lido no offset enviado, então o mesmo período vale para
   * qualquer fuso do cliente.
   */
  private static Instant instantOf(String value, String parameter) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value.trim()).toInstant();
    } catch (DateTimeParseException notIso8601) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR,
          "%s deve ser ISO-8601 com offset, ex.: 2026-02-01T10:00:00Z".formatted(parameter));
    }
  }
}
