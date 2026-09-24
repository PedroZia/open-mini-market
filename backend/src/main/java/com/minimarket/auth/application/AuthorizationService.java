package com.minimarket.auth.application;

import com.minimarket.shared.domain.ForbiddenException;
import com.minimarket.shared.domain.Permission;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;

/**
 * Ponto único de checagem de permissão (§6.4, passo 305). As permissões efetivas chegam como
 * atributo da identidade que o {@code BearerTokenIdentityProvider} monta a partir da sessão: nada é
 * consultado no banco por requisição, e o RBAC é relido do banco a cada autenticação (§6.4).
 *
 * <p><strong>Deny by default:</strong> identidade anônima, atributo ausente ou com valor de tipo
 * inesperado contam como "sem permissão". Quem chama é o caso de uso ({@link #require}) e, a partir
 * do passo 306, o porteiro declarativo dos resources.
 *
 * <p>O nome do atributo mora aqui, e não em {@code auth/api}, para que o provider aponte para cá —
 * {@code api → application} é a direção normal; o contrário inverteria a camada. O provider mantém
 * o alias {@code PERMISSIONS_ATTRIBUTE} para os leitores existentes.
 */
@ApplicationScoped
public class AuthorizationService {

  /** Atributo da identidade com as permissões efetivas do usuário ({@code Set<String>}). */
  public static final String PERMISSIONS_ATTRIBUTE = "permissions";

  /** Identidade da requisição; fora de uma requisição HTTP é a identidade anônima. */
  @Inject SecurityIdentity identity;

  /**
   * Exige a permissão da operação: sem ela, 403 {@code ACCESS_DENIED}. A exceção carrega o código
   * exigido — é dele que a auditoria do acesso negado (passo 309) monta o evento.
   */
  public void require(Permission permission) {
    if (!has(permission)) {
      throw new ForbiddenException(
          "permissão %s necessária".formatted(permission.code()), permission.code());
    }
  }

  /** Checa sem lançar; identidade anônima ou atributo ausente/inesperado nega. */
  public boolean has(Permission permission) {
    Object attribute = identity.getAttribute(PERMISSIONS_ATTRIBUTE);
    return attribute instanceof Set<?> permissions && permissions.contains(permission.code());
  }
}
