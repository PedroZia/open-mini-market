package com.minimarket.users.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Cria usuário com a regra de negócio do passo 107: normaliza o username, recusa duplicidade,
 * aplica a política mínima de senha, guarda só o hash, atribui os papéis e nasce {@code ACTIVE}.
 * Uma execução = uma transação (§2.2, regra 6); o adaptador nunca abre transação.
 *
 * <p>Auditoria (passo 310a): a criação vira {@code USER_CREATED} na mesma transação, com o id
 * criado em {@code entityId} e username/displayName/roles em {@code details} — o "after" da
 * operação. Senha e hash nunca entram no evento. O ADMIN inicial do passo 115 ganha o evento de
 * graça porque cria o usuário por este caso de uso: sem requisição, ele nasce como operação de
 * sistema (ator nulo, origem {@code SYSTEM}).
 */
@ApplicationScoped
public class CreateUserUseCase {

  /** Política mínima: só tamanho; maiúscula/símbolo não fazem parte do escopo. */
  private static final int MIN_PASSWORD_LENGTH = 8;

  /** Status com que todo usuário criado nasce; a API devolve no corpo da criação. */
  public static final String STATUS_ACTIVE = "ACTIVE";

  /** Ação do usuário criado (§7.2). */
  private static final String USER_CREATED_ACTION = "USER_CREATED";

  /** Alvo dos eventos de administração de usuário (§7.2). */
  private static final String USER_ENTITY_TYPE = "USER";

  @Inject UserStore userStore;

  @Inject RoleStore roleStore;

  @Inject PasswordHasher passwordHasher;

  /** Auditoria da administração de acesso (passo 310a), na transação da criação. */
  @Inject AuditRecorder auditRecorder;

  @Transactional
  public CreateUserResult execute(CreateUserCommand command) {
    String username = normalizeUsername(command.username());
    if (userStore.existsByUsername(username)) {
      throw new ConflictException(
          ErrorCode.USERNAME_ALREADY_EXISTS, "username %s já está em uso".formatted(username));
    }
    requireValidPassword(command.password());
    List<String> roles = RoleCodes.normalize(command.roleCodes());

    UUID id =
        userStore.insert(
            new NewUser(
                username,
                command.displayName(),
                passwordHasher.hash(command.password()),
                STATUS_ACTIVE));
    roleStore.assignRoles(id, roles);
    auditRecorder.record(
        USER_CREATED_ACTION,
        USER_ENTITY_TYPE,
        id,
        null,
        Map.of("username", username, "displayName", command.displayName(), "roles", roles));

    return new CreateUserResult(id, username, command.displayName(), roles);
  }

  /** Username é sempre comparado em minúsculas e sem espaços nas pontas (§5.3). */
  private static String normalizeUsername(String username) {
    return username == null ? null : username.trim().toLowerCase(Locale.ROOT);
  }

  private static void requireValidPassword(String password) {
    if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR,
          "senha deve ter ao menos %d caracteres".formatted(MIN_PASSWORD_LENGTH));
    }
  }
}
