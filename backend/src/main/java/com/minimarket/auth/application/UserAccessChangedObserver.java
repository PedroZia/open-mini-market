package com.minimarket.auth.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.users.application.UserAccessChangedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.Map;

/**
 * Revoga todas as sessões vivas do usuário quando o acesso dele muda (passo 213): desativação,
 * reset de senha ou corte pelo ADMIN. O observer é síncrono de propósito — roda na mesma thread e
 * na mesma transação de quem publicou, então a mudança e a revogação comitam juntas ou nenhuma das
 * duas fica (nada de {@code @ObservesAsync}, fila ou broker, §8).
 *
 * <p>É por este observer que {@code users} avisa {@code auth} sem depender dele: o evento atravessa
 * a fronteira no sentido permitido (auth → users) e o grafo de módulos segue acíclico.
 *
 * <p>Auditoria (passo 304): o corte em massa vira um único evento {@code SESSION_REVOKED} por
 * execução, apontando o usuário afetado — não há uma sessão única para apontar — e com o motivo e a
 * contagem de sessões derrubadas em {@code details}. A revogação de uma sessão específica pelo
 * passo 210 tem o evento próprio em {@code RevokeSessionUseCase}. O ator é quem publicou o evento
 * (o ADMIN, ou ninguém quando o corte nasce de uma operação de sistema), pelo contexto do passo
 * 302.
 */
@ApplicationScoped
public class UserAccessChangedObserver {

  /** Ação da revogação de sessão (§7.2). */
  private static final String SESSION_REVOKED_ACTION = "SESSION_REVOKED";

  /** Alvo do corte em massa: o usuário cujas sessões caíram. */
  private static final String USER_ENTITY_TYPE = "USER";

  @Inject AuthSessionStore sessionStore;

  /** Auditoria do acesso (passo 304): um evento por corte, com a contagem em {@code details}. */
  @Inject AuditRecorder auditRecorder;

  /** Relógio da aplicação: o instante da revogação é decisão do caso de uso, não do banco. */
  @Inject Clock clock;

  /** Revoga as sessões ativas do usuário com o motivo que o evento carrega. */
  public void onUserAccessChanged(@Observes UserAccessChangedEvent event) {
    int revokedCount =
        sessionStore.revokeAllByUser(event.userId(), event.reason(), clock.instant());
    auditRecorder.record(
        SESSION_REVOKED_ACTION,
        USER_ENTITY_TYPE,
        event.userId(),
        event.reason(),
        Map.of("reason", event.reason(), "revokedCount", revokedCount));
  }
}
