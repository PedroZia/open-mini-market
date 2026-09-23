package com.minimarket.auth.application;

import java.util.UUID;

/**
 * Porta de persistência da sessão (passo 204a); o adaptador JPA fica em {@code
 * auth.infrastructure}. Só tipos de aplicação atravessam: entidade JPA nunca chega aqui. As
 * leituras e revogações entram quando os passos 206+ existirem.
 */
public interface AuthSessionStore {

  /** Insere a sessão e devolve o id gerado (UUIDv7) pelo adaptador. */
  UUID insert(NewAuthSession session);
}
