package com.minimarket.cash.api;

import com.minimarket.cash.application.CashRegisterView;
import com.minimarket.cash.application.ListCashRegistersUseCase;
import com.minimarket.shared.api.RequirePermission;
import com.minimarket.shared.domain.Permission;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * Caixas físicos (§9.3 do plano). A API valida forma, delega ao caso de uso e mapeia a resposta —
 * zero regra de negócio aqui.
 *
 * <p>A leitura exige {@code cash.read} (a TUI escolhe o caixa no login): sem a permissão o
 * interceptor do {@code RequirePermission} responde 403 {@code ACCESS_DENIED} antes de o corpo do
 * método rodar.
 *
 * <p>A listagem é um array simples, sem paginação: o §9.3 não define {@code page}/{@code size} para
 * esta rota e são poucos caixas por loja — a ordenação por código vem do repositório.
 */
@Path(CashRegistersResource.PATH)
public class CashRegistersResource {

  /** Caminho do recurso (§9.3); o path carrega a versão da API (§9.1). */
  public static final String PATH = "/api/v1/cash-registers";

  @Inject ListCashRegistersUseCase listCashRegistersUseCase;

  /** Caixas ativos, cada um com o status da sessão atual e o operador dela. */
  @GET
  @RequirePermission(Permission.CASH_READ)
  @Produces(MediaType.APPLICATION_JSON)
  public List<CashRegisterResponse> list() {
    return listCashRegistersUseCase.execute().stream()
        .map(CashRegistersResource::toResponse)
        .toList();
  }

  private static CashRegisterResponse toResponse(CashRegisterView register) {
    return new CashRegisterResponse(
        register.id(),
        register.code(),
        register.name(),
        register.status(),
        register.operatorName());
  }
}
