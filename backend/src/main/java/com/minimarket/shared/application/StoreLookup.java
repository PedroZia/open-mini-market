package com.minimarket.shared.application;

import com.minimarket.shared.domain.Store;
import java.util.Optional;
import java.util.UUID;

/** Porta de leitura da loja; o adaptador JPA fica em {@code shared.infrastructure}. */
public interface StoreLookup {

  /** Busca a loja pelo código exato; vazio quando a configuração aponta para loja inexistente. */
  Optional<Store> findByCode(String code);

  /**
   * Busca a loja pelo id (passo 207): a sessão guarda {@code store_id} (§5.3) e o {@code /auth/me}
   * devolve o código e o nome da loja da sessão. Vazio para id desconhecido.
   */
  Optional<Store> findById(UUID id);
}
