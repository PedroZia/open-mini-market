package com.minimarket.auth.application;

import com.minimarket.users.application.UserAccessChangedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Clock;

/**
 * Revoga todas as sessões vivas do usuário quando o acesso dele muda (passo 213): desativação,
 * reset de senha ou corte pelo ADMIN. O observer é síncrono de propósito — roda na mesma thread e
 * na mesma transação de quem publicou, então a mudança e a revogação comitam juntas ou nenhuma das
 * duas fica (nada de {@code @ObservesAsync}, fila ou broker, §8).
 *
 * <p>É por este observer que {@code users} avisa {@code auth} sem depender dele: o evento atravessa
 * a fronteira no sentido permitido (auth → users) e o grafo de módulos segue acíclico.
 */
@ApplicationScoped
public class UserAccessChangedObserver {

  @Inject AuthSessionStore sessionStore;

  /** Relógio da aplicação: o instante da revogação é decisão do caso de uso, não do banco. */
  @Inject Clock clock;

  /** Revoga as sessões ativas do usuário com o motivo que o evento carrega. */
  public void onUserAccessChanged(@Observes UserAccessChangedEvent event) {
    sessionStore.revokeAllByUser(event.userId(), event.reason(), clock.instant());
  }
}
