package com.minimarket.shared.api;

import com.minimarket.shared.application.GetMetaUseCase;
import com.minimarket.shared.domain.Store;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * Metadados públicos da aplicação (§9.3 do plano): parâmetros de negócio que os clientes consultam
 * para não duplicar regra. A rota fica fora da autenticação até a Fase 3 (exceção do passo 308).
 */
@Path(MetaResource.PATH)
public class MetaResource {

  /** Versão da API exposta no path (§9.1): mudança incompatível cria {@code /api/v2}. */
  public static final String API_VERSION = "v1";

  static final String PATH = "/api/v1/meta";

  @Inject GetMetaUseCase getMetaUseCase;

  @GET
  public MetaResponse get() {
    GetMetaUseCase.Meta meta = getMetaUseCase.execute();
    Store store = meta.store();
    return new MetaResponse(
        API_VERSION,
        store.code(),
        store.allowNegativeStock(),
        store.maxDiscountPercent(),
        meta.serverTime());
  }
}
