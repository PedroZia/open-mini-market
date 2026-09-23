package com.minimarket;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
class RbacMigrationTest extends IntegrationTestBase {

  private static final Set<String> PERMISSION_CATALOG =
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

  private static final Set<String> OPERADOR_PERMISSIONS =
      Set.of(
          "product.read",
          "sale.create",
          "payment.add",
          "sale.complete",
          "cash.read",
          "cash.open",
          "cash.close",
          "customer.read",
          "customer.write",
          "stock.read");

  private static final Set<String> GERENTE_PERMISSIONS =
      Set.of(
          "product.read",
          "sale.create",
          "payment.add",
          "sale.complete",
          "cash.read",
          "cash.open",
          "cash.close",
          "customer.read",
          "customer.write",
          "stock.read",
          "sale.discount.apply",
          "sale.cancel",
          "sale.refund",
          "price.write",
          "product.write",
          "category.write",
          "stock.adjust",
          "stock.receive",
          "cash.withdrawal",
          "cash.supply",
          "report.read",
          "audit.read");

  @Test
  @DisplayName("a migration V3 semeia as 3 roles de sistema e as 26 permissões do catálogo")
  void seedsRolesAndPermissionCatalog() throws Exception {
    assertThat(roleCodes())
        .as("roles de §4.5")
        .containsExactlyInAnyOrderElementsOf(Set.of("ADMIN", "GERENTE", "OPERADOR"));
    assertThat(systemRoleCodes())
        .as("as 3 roles embutidas nascem com system = true")
        .containsExactlyInAnyOrderElementsOf(Set.of("ADMIN", "GERENTE", "OPERADOR"));
    assertThat(permissionCodes())
        .as("catálogo de permissões de §4.5")
        .containsExactlyInAnyOrderElementsOf(PERMISSION_CATALOG);
  }

  @Test
  @DisplayName("OPERADOR tem exatamente as 10 permissões do mapa inicial")
  void operadorHasInitialMap() throws Exception {
    assertThat(permissionsOf("OPERADOR")).containsExactlyInAnyOrderElementsOf(OPERADOR_PERMISSIONS);
  }

  @Test
  @DisplayName("GERENTE tem as 22 permissões do mapa inicial (OPERADOR + 12)")
  void gerenteHasInitialMap() throws Exception {
    assertThat(permissionsOf("GERENTE")).containsExactlyInAnyOrderElementsOf(GERENTE_PERMISSIONS);
  }

  @Test
  @DisplayName("ADMIN possui todas as 26 permissões do catálogo")
  void adminHasAllPermissions() throws Exception {
    assertThat(permissionsOf("ADMIN")).containsExactlyInAnyOrderElementsOf(PERMISSION_CATALOG);
  }

  private Set<String> roleCodes() throws Exception {
    return codes("select code from roles");
  }

  private Set<String> systemRoleCodes() throws Exception {
    return codes("select code from roles where system");
  }

  private Set<String> permissionCodes() throws Exception {
    return codes("select code from permissions");
  }

  private Set<String> codes(String sql) throws Exception {
    Set<String> codes = new HashSet<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      while (resultSet.next()) {
        codes.add(resultSet.getString(1));
      }
    }
    return codes;
  }

  private Set<String> permissionsOf(String roleCode) throws Exception {
    Set<String> codes = new HashSet<>();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select p.code from permissions p"
                    + " join role_permissions rp on rp.permission_id = p.id"
                    + " join roles r on r.id = rp.role_id"
                    + " where r.code = ?")) {
      statement.setString(1, roleCode);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          codes.add(resultSet.getString("code"));
        }
      }
    }
    return codes;
  }
}
