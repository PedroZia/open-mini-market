package com.minimarket.users.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Map;
import java.util.UUID;

/**
 * Reativa o usuário desativado (passo 112): o adaptador devolve {@code status = ACTIVE} e limpa
 * {@code deleted_at}, o que traz o usuário de volta à busca padrão. Usuário já ativo é no-op e
 * responde 200 igual. Uma execução = uma transação (§2.2, regra 6).
 *
 * <p>Auditoria (passo 310a): a reativação que de fato acontece vira {@code USER_ENABLED} na mesma
 * transação, com o antes/depois mínimo de status (§7.2); o no-op de quem já está ativo não inventa
 * evento — mesmo precedente documentado no {@code RevokeSessionUseCase}. O "before" sai da leitura
 * anterior: {@link UserStore#findSummaryById} não enxerga o soft-deletado, então vazio é o usuário
 * que a reativação estava restaurando.
 */
@ApplicationScoped
public class EnableUserUseCase {

  /** Ação da reativação (§7.2). */
  private static final String USER_ENABLED_ACTION = "USER_ENABLED";

  /** Alvo dos eventos de administração de usuário (§7.2). */
  private static final String USER_ENTITY_TYPE = "USER";

  /** Status de quem está desativado, espelhando o check constraint de {@code users.status}. */
  private static final String STATUS_DISABLED = "DISABLED";

  @Inject UserStore userStore;

  /** Auditoria da administração de acesso (passo 310a), na transação da reativação. */
  @Inject AuditRecorder auditRecorder;

  /** 404 {@code USER_NOT_FOUND} quando o id não existe; id desativado é reativado normalmente. */
  @Transactional
  public UserSummary execute(UUID id) {
    boolean wasDisabled = userStore.findSummaryById(id).isEmpty();
    UserSummary enabled =
        userStore
            .enable(id)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        ErrorCode.USER_NOT_FOUND, "usuário %s não encontrado".formatted(id)));
    if (wasDisabled) {
      auditRecorder.record(
          USER_ENABLED_ACTION,
          USER_ENTITY_TYPE,
          id,
          null,
          Map.of(
              "before", Map.of("status", STATUS_DISABLED),
              "after", Map.of("status", enabled.status())));
    }
    return enabled;
  }
}
