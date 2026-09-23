package com.minimarket.users.application;

import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.UUID;

/**
 * Corta todas as sessões vivas do usuário a pedido do ADMIN (passo 213). Uma execução = uma
 * transação (§2.2, regra 6): o usuário é validado e o evento síncrono que revoga as sessões roda
 * dentro dela — usuário inexistente responde 404 sem revogar nada.
 *
 * <p>A revogação em si é do módulo {@code auth}, que observa {@link UserAccessChangedEvent}; {@code
 * users} publica o evento e não o conhece, porque a dependência entre os dois módulos só pode ir de
 * {@code auth} para {@code users} (§2.2).
 */
@ApplicationScoped
public class RevokeUserSessionsUseCase {

  /** Motivo gravado em {@code revoked_reason} das sessões cortadas pela ação do ADMIN. */
  public static final String ADMIN_REVOKE_REASON = "ADMIN_REVOKE";

  @Inject UserStore userStore;

  /** Evento síncrono do corte de acesso (passo 213): a revogação das sessões é do módulo auth. */
  @Inject Event<UserAccessChangedEvent> accessChanged;

  /**
   * 404 {@code USER_NOT_FOUND} quando não existe usuário vivo com o id — já desativado conta como
   * inexistente, como nas demais ações do módulo (o soft delete sai junto com o disable).
   */
  @Transactional
  public void execute(UUID id) {
    userStore.findSummaryById(id).orElseThrow(() -> notFound(id));
    accessChanged.fire(new UserAccessChangedEvent(id, ADMIN_REVOKE_REASON));
  }

  private static NotFoundException notFound(UUID id) {
    return new NotFoundException(
        ErrorCode.USER_NOT_FOUND, "usuário %s não encontrado".formatted(id));
  }
}
