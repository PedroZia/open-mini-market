package com.minimarket.sales.api;

import com.minimarket.sales.application.CreateSaleCommand;
import com.minimarket.sales.application.CreateSaleUseCase;
import com.minimarket.sales.domain.Sale;
import com.minimarket.shared.api.IdempotencyGuard;
import com.minimarket.shared.api.RequirePermission;
import com.minimarket.shared.application.OperationContext;
import com.minimarket.shared.domain.Permission;
import jakarta.inject.Inject;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Vendas (§9.3 do plano): a abertura da venda pela API/TUI (passo 807). A API valida forma, delega
 * ao caso de uso (passo 805) e mapeia a resposta — zero regra de negócio aqui.
 *
 * <p>A rota exige {@code sale.create} (BR-10): sem a permissão o interceptor do {@code
 * RequirePermission} responde 403 {@code ACCESS_DENIED} antes de o corpo do método rodar.
 *
 * <p><strong>Sem corpo de requisição.</strong> O {@code POST /sales} é o gesto "abrir venda":
 * caixa, operador e loja saem da sessão autenticada (§9.3) — o cliente não escolhe nenhum deles
 * (BR-06, BR-11) — e o número sai do alocador do servidor. Por isso a rota não declara
 * {@code @Consumes}: um POST sem corpo não deve exigir {@code Content-Type}. Se um dia houver
 * observações na abertura, elas entram no corpo; até lá não há contrato de entrada.
 *
 * <p>Abrir venda é operação idempotente por contrato (§8): o {@link IdempotencyGuard} exige o
 * header {@code Idempotency-Key} (sem ele, 400 {@code IDEMPOTENCY_KEY_REQUIRED}) e o retry com a
 * mesma chave devolve a resposta gravada com {@code Idempotency-Replayed: true}, sem abrir outra
 * venda. O payload é nulo — é ele que o guard hasheia, então duas chamadas da mesma sessão são
 * sempre a mesma requisição.
 *
 * <p>Sessão sem caixa vinculado é 403 e caixa sem sessão aberta é 409 {@code
 * CASH_SESSION_REQUIRED}, ambos do caso de uso. O 201 é <em>sem</em> {@code Location}: a rota de
 * leitura da venda só nasce no passo 812, e o corpo já traz o id — apontar para uma rota
 * inexistente seria um contrato falso.
 */
@Path(SalesResource.PATH)
public class SalesResource {

  /** Caminho do recurso (§9.3); o path carrega a versão da API (§9.1). */
  public static final String PATH = "/api/v1/sales";

  @Inject CreateSaleUseCase createSaleUseCase;

  /** Idempotência da abertura (§8, passo 807): a chave identifica o gesto de abrir a venda. */
  @Inject IdempotencyGuard idempotencyGuard;

  /** O ator da requisição: o caixa vinculado e o operador saem daqui, não do cliente (BR-11). */
  @Inject OperationContext operationContext;

  /**
   * Abre a venda no caixa da sessão autenticada e devolve 201 com o cabeçalho da venda nascida. Sem
   * vínculo de caixa o caso de uso recusa com 403 e sem sessão de caixa aberta, 409 {@code
   * CASH_SESSION_REQUIRED} — a API não antecipa nenhuma das duas checagens.
   */
  @POST
  @RequirePermission(Permission.SALE_CREATE)
  @Produces(MediaType.APPLICATION_JSON)
  public Response create(@HeaderParam(IdempotencyGuard.KEY_HEADER) String idempotencyKey) {
    return idempotencyGuard.execute(idempotencyKey, HttpMethod.POST, PATH, null, this::openSale);
  }

  /** Ação idempotente: abre a venda e monta o 201 com a projeção do caso de uso. */
  private Response openSale() {
    Sale sale =
        createSaleUseCase.execute(
            new CreateSaleCommand(operationContext.cashRegisterId(), operationContext.userId()));
    return Response.status(Response.Status.CREATED).entity(toResponse(sale)).build();
  }

  private static SaleResponse toResponse(Sale sale) {
    return new SaleResponse(
        sale.id(),
        sale.number(),
        sale.status(),
        sale.cashSessionId(),
        sale.cashRegisterId(),
        sale.operatorUserId(),
        sale.subtotal(),
        sale.discountAmount(),
        sale.total(),
        sale.itemCount(),
        sale.createdAt());
  }
}
