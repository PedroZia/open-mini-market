package com.minimarket.shared.application;

import com.minimarket.shared.domain.Store;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Resolve os parâmetros de negócio que TUI e Web precisam para operar sem duplicar regra (§9.3 do
 * plano): a loja vem da configuração e o horário é o do servidor, em UTC.
 */
@ApplicationScoped
public class GetMetaUseCase {

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  @Transactional
  public Meta execute() {
    Store store =
        storeLookup
            .findByCode(defaultStoreCode)
            .orElseThrow(
                () ->
                    new IllegalStateException("loja configurada não existe: " + defaultStoreCode));
    return new Meta(store, Instant.now());
  }

  /** Resultado do caso de uso: loja atual e hora do servidor. */
  public record Meta(Store store, Instant serverTime) {}
}
