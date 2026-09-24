package com.minimarket.shared.domain;

/**
 * Catálogo de permissões de §4.5 do plano: cada constante carrega o código textual idêntico ao de
 * {@code permissions.code} (seed da migration V3) — é esse texto que viaja no atributo da
 * identidade e é comparado pelo {@code AuthorizationService} (passo 305).
 *
 * <p>Mora em {@code shared}, e não em {@code auth}, porque os módulos de negócio (users, catalog,
 * cash...) exigem permissões nos próprios casos de uso e um resource de {@code users} que
 * importasse {@code auth} fecharia ciclo de módulos ({@code auth} já depende de {@code users}).
 */
public enum Permission {
  USER_READ("user.read"),
  USER_WRITE("user.write"),
  ROLE_WRITE("role.write"),
  PRODUCT_READ("product.read"),
  PRODUCT_WRITE("product.write"),
  PRICE_WRITE("price.write"),
  CATEGORY_WRITE("category.write"),
  STOCK_READ("stock.read"),
  STOCK_ADJUST("stock.adjust"),
  STOCK_RECEIVE("stock.receive"),
  SALE_CREATE("sale.create"),
  SALE_DISCOUNT_APPLY("sale.discount.apply"),
  SALE_CANCEL("sale.cancel"),
  SALE_REFUND("sale.refund"),
  PAYMENT_ADD("payment.add"),
  SALE_COMPLETE("sale.complete"),
  CASH_READ("cash.read"),
  CASH_OPEN("cash.open"),
  CASH_CLOSE("cash.close"),
  CASH_WITHDRAWAL("cash.withdrawal"),
  CASH_SUPPLY("cash.supply"),
  CUSTOMER_READ("customer.read"),
  CUSTOMER_WRITE("customer.write"),
  AUDIT_READ("audit.read"),
  REPORT_READ("report.read"),
  USER_SESSION_REVOKE("user.session.revoke");

  private final String code;

  Permission(String code) {
    this.code = code;
  }

  /** Código textual da permissão, o mesmo de {@code permissions.code}. */
  public String code() {
    return code;
  }
}
