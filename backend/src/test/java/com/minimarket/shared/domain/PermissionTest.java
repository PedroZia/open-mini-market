package com.minimarket.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Trava o catálogo do enum contra o de §4.5 — o mesmo que a migration V3 semeia e o {@code
 * RbacMigrationTest} confere no banco. Um código com erro de digitação aqui viraria 403 silencioso
 * em produção, sem nenhum outro teste reclamar.
 */
class PermissionTest {

  private static final Set<String> CATALOG =
      Set.of(
          "user.read",
          "user.write",
          "role.write",
          "product.read",
          "product.write",
          "price.write",
          "category.write",
          "stock.read",
          "stock.adjust",
          "stock.receive",
          "sale.create",
          "sale.discount.apply",
          "sale.cancel",
          "sale.refund",
          "payment.add",
          "sale.complete",
          "cash.read",
          "cash.open",
          "cash.close",
          "cash.withdrawal",
          "cash.supply",
          "customer.read",
          "customer.write",
          "audit.read",
          "report.read",
          "user.session.revoke");

  @Test
  @DisplayName("o enum tem exatamente os 26 códigos de §4.5, sem duplicatas")
  void matchesCatalog() {
    assertThat(Arrays.stream(Permission.values()).map(Permission::code).toList())
        .containsExactlyInAnyOrderElementsOf(CATALOG);
  }
}
