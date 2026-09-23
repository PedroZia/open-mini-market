package com.minimarket.users.infrastructure;

import com.minimarket.users.application.EnsureInitialAdminUseCase;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Semeia o ADMIN inicial no startup (passo 115). Adaptador fino, sem regra: a decisão de criar ou
 * não é do {@link EnsureInitialAdminUseCase}; aqui só se lê a configuração e se registra o
 * resultado.
 *
 * <p>Sem senha inicial configurada o observador é no-op: em {@code %dev} a senha tem default
 * (documentado no README), em {@code %test} e {@code %prod} só existe se o ambiente definir a
 * variável — o perfil de teste padrão não pode ganhar um ADMIN semeado, senão a regra do último
 * ADMIN ativo (passo 112) muda de resultado.
 */
@ApplicationScoped
public class InitialAdminInitializer {

  private static final Logger LOG = Logger.getLogger(InitialAdminInitializer.class);

  @Inject EnsureInitialAdminUseCase ensureInitialAdminUseCase;

  @ConfigProperty(name = "minimarket.security.admin.username")
  String username;

  /** Ausente (ou vazia) nos perfis sem default: é o que mantém o inicializador em silêncio. */
  @ConfigProperty(name = "minimarket.security.admin.initial-password")
  Optional<String> initialPassword;

  void onStart(@Observes StartupEvent event) {
    String password = initialPassword.filter(value -> !value.isBlank()).orElse(null);
    if (password == null) {
      return;
    }
    if (ensureInitialAdminUseCase.execute(username, password)) {
      LOG.infof(
          "ADMIN inicial '%s' criado; a troca da senha é obrigatória no primeiro login", username);
    }
  }
}
