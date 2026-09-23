package com.minimarket.auth.domain;

/**
 * Cliente de origem da sessão (§6.2): a TUI opera em turno contínuo e o Web em sessão curta — cada
 * um tem seu idle timeout configurado em {@code minimarket.security.session.idle.*}. Os nomes são
 * os aceitos pelo check constraint de {@code auth_sessions.client}.
 */
public enum SessionClient {
  TUI,
  WEB
}
