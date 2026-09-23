package com.minimarket.shared.application;

import com.minimarket.shared.domain.Store;
import java.util.Optional;

/** Porta de leitura da loja; o adaptador JPA fica em {@code shared.infrastructure}. */
public interface StoreLookup {

  /** Busca a loja pelo código exato; vazio quando a configuração aponta para loja inexistente. */
  Optional<Store> findByCode(String code);
}
