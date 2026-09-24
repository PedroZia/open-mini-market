package com.minimarket.auth.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.users.application.PasswordHasher;
import com.minimarket.users.application.UserAuthState;
import com.minimarket.users.application.UserStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Troca da própria senha (passo 214): exige a senha atual, aplica a política da nova, limpa {@code
 * mustChangePassword} e derruba as outras sessões do usuário. Uma execução = uma transação (§2.2,
 * regra 6) — hash novo, carimbo da troca e revogação das demais sessões saem juntos ou não saem.
 *
 * <p>A sessão é relida pelo id que a identidade carrega, como no passo 207: sessão revogada entre a
 * autenticação e a troca responde o 401 genérico de token inválido, e é dela que vem o {@code
 * userId} — o username não serve de chave aqui porque o soft delete libera o nome para reuso
 * (§5.3).
 *
 * <p>Senha atual incorreta responde 400 {@code INVALID_CURRENT_PASSWORD} com mensagem única, sem
 * revelar mais nada (§6.3.4). A senha nova é hasheada de novo (Argon2id, §6.2): rehash de hash
 * antigo não se aplica, a senha mudou de verdade.
 *
 * <p>A sessão que fez a troca sobrevive — derrubá-la obrigaria o usuário a logar de novo logo
 * depois de provar que conhece a senha atual; as demais caem com o motivo {@code PASSWORD_CHANGED},
 * como manda a revogação por troca de senha (§6.2).
 *
 * <p>Auditoria (§7.2): a troca vira {@code PASSWORD_CHANGED} na mesma transação, com o usuário da
 * sessão em {@code entityId} e só o {@code username} em {@code details} — senha e hash nunca entram
 * no evento. O corte em massa continua sem evento próprio: o rastro dele é o {@code revoked_reason}
 * das sessões derrubadas.
 */
@ApplicationScoped
public class ChangeOwnPasswordUseCase {

  /**
   * Mesma política mínima de {@code CreateUserUseCase}/{@code ResetPasswordUseCase}: só tamanho.
   */
  private static final int MIN_PASSWORD_LENGTH = 8;

  /** Motivo gravado em {@code revoked_reason} das sessões derrubadas pela troca de senha. */
  public static final String PASSWORD_CHANGED_REASON = "PASSWORD_CHANGED";

  /**
   * Ação da troca de senha (§7.2). O valor é o mesmo do motivo da revogação — o §6.2 nomeia a troca
   * assim dos dois lados; a constante separada mantém cada uso com o seu significado.
   */
  private static final String PASSWORD_CHANGED_ACTION = "PASSWORD_CHANGED";

  /** Alvo do evento da troca: o usuário dono da sessão. */
  private static final String USER_ENTITY_TYPE = "USER";

  /** Mensagem única da senha atual recusada; não revela nada além disso (§6.3.4). */
  private static final String INVALID_CURRENT_PASSWORD_DETAIL = "senha atual incorreta";

  @Inject UserStore userStore;

  @Inject AuthSessionStore sessionStore;

  @Inject PasswordHasher passwordHasher;

  /** Auditoria da troca (§7.2), na transação da senha nova e da revogação. */
  @Inject AuditRecorder auditRecorder;

  /** Relógio da aplicação: o instante da revogação é decisão do caso de uso. */
  @Inject Clock clock;

  /**
   * Troca a senha do dono da sessão: 400 {@code VALIDATION_ERROR} quando a senha nova é curta
   * demais, 400 {@code INVALID_CURRENT_PASSWORD} quando a senha atual não confere e 401 genérico
   * quando a sessão deixou de existir (mesmo 401 do passo 207).
   */
  @Transactional
  public void execute(UUID sessionId, String currentPassword, String newPassword) {
    AuthSessionSnapshot session =
        sessionStore.findActiveById(sessionId).orElseThrow(ChangeOwnPasswordUseCase::invalidToken);
    requireValidPassword(newPassword);
    UserAuthState user =
        userStore
            .findAuthStateById(session.userId())
            .orElseThrow(ChangeOwnPasswordUseCase::invalidToken);
    if (!passwordHasher.verify(currentPassword, user.passwordHash())) {
      throw new BusinessException(
          ErrorCode.INVALID_CURRENT_PASSWORD, INVALID_CURRENT_PASSWORD_DETAIL);
    }
    userStore.changeOwnPassword(user.id(), passwordHasher.hash(newPassword));
    sessionStore.revokeAllByUserExcept(
        user.id(), sessionId, PASSWORD_CHANGED_REASON, clock.instant());
    auditRecorder.record(
        PASSWORD_CHANGED_ACTION,
        USER_ENTITY_TYPE,
        user.id(),
        null,
        Map.of("username", user.username()));
  }

  private static void requireValidPassword(String password) {
    if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR,
          "senha deve ter ao menos %d caracteres".formatted(MIN_PASSWORD_LENGTH));
    }
  }

  /** Sessão revogada e usuário inexistente são o mesmo 401 genérico do passo 206. */
  private static BusinessException invalidToken() {
    return new BusinessException(
        ErrorCode.INVALID_CREDENTIALS, AuthenticateSessionUseCase.INVALID_TOKEN_DETAIL);
  }
}
