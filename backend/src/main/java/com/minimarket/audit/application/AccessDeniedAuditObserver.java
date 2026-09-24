package com.minimarket.audit.application;

import com.minimarket.shared.application.AccessDeniedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Auditoria do acesso negado (passo 309): o 403 que o {@code BusinessExceptionMapper} publica vira
 * um evento {@code ACCESS_DENIED} no log, com rota, método e permissão exigida em {@code details} e
 * o ator/loja/IP/sessão vindos do {@code OperationContext} da requisição (passo 302).
 *
 * <p>O observer é síncrono — mesma thread do request — e só delega: quem grava é o {@link
 * AccessDeniedAuditRecorder}, em transação própria. É por evento que o {@code shared} avisa o
 * {@code audit} sem depender dele; o contrário fecharia ciclo de módulos (§2.2).
 */
@ApplicationScoped
public class AccessDeniedAuditObserver {

  @Inject AccessDeniedAuditRecorder recorder;

  /** Publicado a cada 403 de permissão; o registro não é opcional nem silenciável (§7.1). */
  public void onAccessDenied(@Observes AccessDeniedEvent event) {
    recorder.record(event);
  }
}
