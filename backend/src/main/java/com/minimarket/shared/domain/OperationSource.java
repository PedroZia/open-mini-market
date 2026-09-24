package com.minimarket.shared.domain;

/**
 * Origem de uma operação (§7.2, passo 302): os mesmos valores aceitos pelo check constraint de
 * {@code audit_events.source}. {@code API} é a requisição HTTP sem sessão de PDV (login, meta) ou
 * sem cliente identificado; {@code TUI} e {@code WEB} são o cliente da sessão autenticada; {@code
 * SYSTEM} é o que não nasceu de requisição nenhuma (tarefas internas e inicializadores).
 */
public enum OperationSource {
  API,
  TUI,
  WEB,
  SYSTEM
}
