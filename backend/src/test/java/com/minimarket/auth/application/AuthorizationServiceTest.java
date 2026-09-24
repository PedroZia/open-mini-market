package com.minimarket.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.ForbiddenException;
import com.minimarket.shared.domain.Permission;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link AuthorizationService}, sem container e sem banco: a identidade é
 * montada à mão com o mesmo builder que o provider bearer usa (passo 206), então o aceite "com
 * identidade fake: permissão presente passa, ausente lança 403" não depende de HTTP.
 */
class AuthorizationServiceTest {

  private final AuthorizationService service = new AuthorizationService();

  @Test
  @DisplayName("permissão presente na identidade passa em require e é vista por has")
  void allowsPresentPermission() {
    service.identity = identityWith(Set.of("sale.create", "cash.open"));

    assertThatCode(() -> service.require(Permission.SALE_CREATE)).doesNotThrowAnyException();
    assertThat(service.has(Permission.SALE_CREATE)).isTrue();
    assertThat(service.has(Permission.CASH_WITHDRAWAL)).isFalse();
  }

  @Test
  @DisplayName("permissão ausente lança 403 ACCESS_DENIED citando a permissão exigida")
  void deniesAbsentPermission() {
    service.identity = identityWith(Set.of("sale.create"));

    assertThatThrownBy(() -> service.require(Permission.USER_WRITE))
        .isInstanceOfSatisfying(
            ForbiddenException.class,
            forbidden -> {
              assertThat(forbidden.code()).isEqualTo(ErrorCode.ACCESS_DENIED);
              assertThat(forbidden.code().status()).isEqualTo(403);
              assertThat(forbidden).hasMessageContaining(Permission.USER_WRITE.code());
            });
  }

  @Test
  @DisplayName("identidade anônima nega por padrão: has é false e require lança 403")
  void deniesAnonymousIdentity() {
    service.identity = QuarkusSecurityIdentity.builder().setAnonymous(true).build();

    assertThat(service.has(Permission.SALE_CREATE)).isFalse();
    assertThatThrownBy(() -> service.require(Permission.SALE_CREATE))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  @DisplayName("identidade autenticada sem o atributo de permissões nega por padrão")
  void deniesIdentityWithoutPermissionsAttribute() {
    service.identity =
        QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal("operador"))
            .addRole("OPERADOR")
            .build();

    assertThat(service.has(Permission.SALE_CREATE)).isFalse();
    assertThatThrownBy(() -> service.require(Permission.SALE_CREATE))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  @DisplayName("atributo de permissões com tipo inesperado nega sem estourar")
  void deniesUnexpectedAttributeType() {
    service.identity =
        QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal("operador"))
            .addAttribute(AuthorizationService.PERMISSIONS_ATTRIBUTE, "sale.create")
            .build();

    assertThatCode(() -> service.has(Permission.SALE_CREATE)).doesNotThrowAnyException();
    assertThat(service.has(Permission.SALE_CREATE)).isFalse();
    assertThatThrownBy(() -> service.require(Permission.SALE_CREATE))
        .isInstanceOf(ForbiddenException.class);
  }

  /**
   * Identidade como o provider bearer monta (passo 206): principal, role e permissões em atributo.
   */
  private static SecurityIdentity identityWith(Set<String> permissions) {
    return QuarkusSecurityIdentity.builder()
        .setPrincipal(new QuarkusPrincipal("operador"))
        .addRole("OPERADOR")
        .addAttribute(AuthorizationService.PERMISSIONS_ATTRIBUTE, permissions)
        .build();
  }
}
