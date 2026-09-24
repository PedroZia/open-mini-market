package com.minimarket.audit.api;

import com.minimarket.audit.application.AuditEventFilter;
import com.minimarket.audit.application.AuditEventPage;
import com.minimarket.audit.application.AuditEventSummary;
import com.minimarket.audit.application.ListAuditEventsUseCase;
import com.minimarket.shared.api.PageResponse;
import com.minimarket.shared.api.QueryParams;
import com.minimarket.shared.api.RequirePermission;
import com.minimarket.shared.domain.Permission;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

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
 * <p>Nenhum filtro usa o conversor implícito do JAX-RS — que devolveria 404 para um valor inválido
 * —: o parse é explícito pelo {@link QueryParams}, e o valor que não é UUID nem ISO-8601 com offset
 * sai como 400 {@code VALIDATION_ERROR} com {@code errors[]} (passo 1007). A ordenação default é
 * {@code occurredat,desc} — a linha do tempo investigada vem do mais recente para o mais antigo.
 */
@Path(AuditEventsResource.PATH)
public class AuditEventsResource {

  /** Caminho do recurso (§9.3); o path carrega a versão da API (§9.1). */
  public static final String PATH = "/api/v1/audit-events";

  @Inject ListAuditEventsUseCase listAuditEventsUseCase;

  /**
   * Lista paginada com os filtros do §9.3. {@code sort} aceita {@code occurredat} com {@code
   * ,asc|desc} opcional (default {@code occurredat,desc}) e {@code size} acima de 100 é limitado;
   * {@code page} negativo, {@code size} menor que 1, {@code sort} fora da whitelist ou filtro
   * tipado inválido → 400 {@code VALIDATION_ERROR} com {@code errors[]}.
   */
  @GET
  @RequirePermission(Permission.AUDIT_READ)
  @Produces(MediaType.APPLICATION_JSON)
  public PageResponse<AuditEventResponse> list(
      @QueryParam("entityType") String entityType,
      @QueryParam("entityId") String entityId,
      @QueryParam("actorUserId") String actorUserId,
      @QueryParam("action") String action,
      @QueryParam("cashSessionId") String cashSessionId,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("sort") String sort,
      @QueryParam("page") @DefaultValue("0") String page,
      @QueryParam("size") @DefaultValue("20") String size) {
    AuditEventPage events =
        listAuditEventsUseCase.execute(
            new AuditEventFilter(
                entityType,
                QueryParams.uuidOf(entityId, "entityId"),
                QueryParams.uuidOf(actorUserId, "actorUserId"),
                action,
                QueryParams.uuidOf(cashSessionId, "cashSessionId"),
                QueryParams.instantOf(from, "from"),
                QueryParams.instantOf(to, "to")),
            sort,
            QueryParams.intOf(page, "page"),
            QueryParams.intOf(size, "size"));
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
}
