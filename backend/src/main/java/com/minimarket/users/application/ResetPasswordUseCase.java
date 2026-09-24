package com.minimarket.users.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Map;
import java.util.UUID;

/**
 * Reset de senha por ADMIN (passo 113): define uma senha temporária, guarda só o hash e marca o
 * usuário para trocá-la no próximo login ({@code mustChangePassword}). Uma execução = uma transação
 * (§2.2, regra 6); o adaptador nunca abre transação.
 *
 * <p>Depois de gravar a senha nova, publica {@link UserAccessChangedEvent} com o motivo {@code
 * PASSWORD_RESET}: o observer de {@code auth} (passo 213) revoga todas as sessões vivas do usuário
 * na mesma transação — quem tinha a senha antiga perde o acesso imediatamente, e quem recebeu a
 * temporária entra com ela.
 *
 * <p>Auditoria (passo 310b): a troca vira {@code PASSWORD_RESET} na mesma transação, com o id do
 * alvo em {@code entityId} e username/mustChangePassword em {@code details} — a senha nova e o hash
 * nunca entram no evento (§7.1/§7.2).
 */
@ApplicationScoped
public class ResetPasswordUseCase {

  /** Mesma política mínima do {@link CreateUserUseCase}: só tamanho. */
  private static final int MIN_PASSWORD_LENGTH = 8;

  /** Motivo gravado em {@code revoked_reason} das sessões derrubadas pelo reset de senha. */
  public static final String PASSWORD_RESET_REASON = "PASSWORD_RESET";

  /** Ação do reset de senha na auditoria (§7.2). */
  private static final String PASSWORD_RESET_ACTION = "PASSWORD_RESET";

  /** Alvo dos eventos de administração de usuário (§7.2). */
  private static final String USER_ENTITY_TYPE = "USER";

  @Inject UserStore userStore;

  @Inject PasswordHasher passwordHasher;

  /** Evento síncrono do corte de acesso (passo 213): a revogação das sessões é do módulo auth. */
  @Inject Event<UserAccessChangedEvent> accessChanged;

  /** Auditoria da administração de acesso (passo 310b), na transação do reset. */
  @Inject AuditRecorder auditRecorder;

  /**
   * 404 {@code USER_NOT_FOUND} quando não existe usuário vivo com o id (soft-deletado conta como
   * inexistente); 400 {@code VALIDATION_ERROR} quando a senha nova é curta demais.
   */
  @Transactional
  public UserSummary execute(UUID id, String newPassword) {
    userStore.findSummaryById(id).orElseThrow(() -> notFound(id));
    requireValidPassword(newPassword);
    UserSummary user =
        userStore
            .resetPassword(id, passwordHasher.hash(newPassword))
            .orElseThrow(() -> notFound(id));
    auditRecorder.record(
        PASSWORD_RESET_ACTION,
        USER_ENTITY_TYPE,
        id,
        null,
        Map.of("username", user.username(), "mustChangePassword", user.mustChangePassword()));
    accessChanged.fire(new UserAccessChangedEvent(id, PASSWORD_RESET_REASON));
    return user;
  }

  private static void requireValidPassword(String password) {
    if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR,
          "senha deve ter ao menos %d caracteres".formatted(MIN_PASSWORD_LENGTH));
    }
  }

  private static NotFoundException notFound(UUID id) {
    return new NotFoundException(
        ErrorCode.USER_NOT_FOUND, "usuário %s não encontrado".formatted(id));
  }
}
