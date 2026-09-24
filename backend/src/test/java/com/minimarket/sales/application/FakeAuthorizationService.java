package com.minimarket.sales.application;

import com.minimarket.auth.application.AuthorizationService;
import com.minimarket.shared.domain.Permission;
import java.util.HashSet;
import java.util.Set;

/**
 * Dublê de {@link AuthorizationService} dos unitários do desconto (passo 810): o caso de uso chama
 * {@code require}, que delega ao {@code has} sobrescrito aqui — a identidade do Quarkus e o
 * atributo de permissões não participam do teste. A classe de verdade não é final de propósito para
 * isto: os cenários de permissão são cobertos sem subir o contêiner.
 */
final class FakeAuthorizationService extends AuthorizationService {

  /** Permissões que o cenário concede ao ator; começa vazio porque o padrão é negar. */
  final Set<Permission> granted = new HashSet<>();

  @Override
  public boolean has(Permission permission) {
    return granted.contains(permission);
  }
}
