package com.minimarket.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.cash.application.OpenCashSessionCommand;
import com.minimarket.cash.application.OpenCashSessionUseCase;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.sales.domain.Sale;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.users.application.NewUser;
import com.minimarket.users.application.UserStore;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link AddSaleItemUseCase} contra PostgreSQL real (Dev Services) — critério de
 * aceite do passo 808: a venda sai do {@code CreateSaleUseCase} e o produto da porta do catálogo, e
 * o teste confere por SQL o que ficou no banco (linha de {@code sale_items} com o snapshot e o
 * {@code line_number}, totais de {@code sales} e evento de auditoria).
 *
 * <p>Sem {@code @TestTransaction}: o caso de uso comita de verdade, como a API fará (passo 809), e
 * a limpeza do {@code @AfterEach} segue a ordem que as FKs exigem: itens → venda → eventos → série
 * → movimentos → sessão de caixa → usuário → produto. A série da venda ({@code document_sequences})
 * é apagada antes e depois de cada teste, porque o banco é compartilhado com os demais testes do
 * fork.
 *
 * <p>O barcode do produto tem sufixo aleatório: o índice único parcial da tabela vale entre
 * execuções e uma linha órfã de um teste anterior não pode derrubar o cenário.
 */
@QuarkusTest
class AddSaleItemIntegrationTest extends IntegrationTestBase {

  private static final String OPERATOR = "vendas.item.808";

  /** Série da venda em {@code document_sequences}; a NFC-e da Fase 14 terá a própria. */
  private static final String SALE_DOC_TYPE = "SALE";

  /** Barcode de 13 dígitos do produto do cenário, sem colidir com o de outra execução. */
  private static final String BARCODE =
      "7891000" + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000)) + "17";

  @Inject AddSaleItemUseCase useCase;

  @Inject CreateSaleUseCase createSaleUseCase;

  @Inject OpenCashSessionUseCase openCashSessionUseCase;

  @Inject ProductStore productStore;

  @Inject UserStore userStore;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  private UUID storeId;

  private UUID userId;

  private UUID productId;

  /** Caixa da sessão do cenário: é o caixa que a guarda de posse confere (passo 809a). */
  private UUID registerId;

  private UUID cashSessionId;

  private final List<UUID> saleIds = new ArrayList<>();

  @Test
  @DisplayName("bipe grava o item com snapshot e line_number 1, atualiza os totais e audita")
  void addsItemWithSnapshotAndUpdatesSaleTotals() throws SQLException {
    Sale sale = openScenario("Arroz 5kg", "UN", "9.90");

    Sale updated =
        useCase.execute(
            new AddSaleItemCommand(
                sale.id(), registerId, " " + BARCODE + " ", null, new BigDecimal("2")));

    assertThat(updated.id()).isEqualTo(sale.id());
    assertThat(updated.items()).hasSize(1);
    assertThat(updated.subtotal()).isEqualByComparingTo("19.80");
    assertThat(updated.total()).isEqualByComparingTo("19.80");
    assertThat(updated.itemCount()).isEqualTo(1);

    List<ItemRow> items = itemRows(sale.id());
    assertThat(items).as("uma linha nova em sale_items").hasSize(1);
    ItemRow item = items.getFirst();
    assertThat(item.lineNumber()).isEqualTo(1);
    assertThat(item.productId()).isEqualTo(productId);
    assertThat(item.barcode()).isEqualTo(BARCODE);
    assertThat(item.name()).isEqualTo("Arroz 5kg");
    assertThat(item.unit()).isEqualTo("UN");
    assertThat(item.unitPrice()).isEqualTo("9.90");
    assertThat(item.quantity()).isEqualTo("2.000");
    assertThat(item.discountAmount()).as("desconto do item é do passo 810").isEqualTo("0.00");
    assertThat(item.lineTotal()).isEqualTo("19.80");

    SaleRow stored = saleRow(sale.id());
    assertThat(stored.subtotal()).isEqualTo("19.80");
    assertThat(stored.discountAmount()).isEqualTo("0.00");
    assertThat(stored.total()).isEqualTo("19.80");
    assertThat(stored.itemCount()).isEqualTo(1);
    assertThat(stored.version()).as("o update da venda avança o lock otimista").isEqualTo(1);

    Event event = eventOf(sale.id());
    assertThat(event.action()).isEqualTo("SALE_ITEM_ADDED");
    assertThat(event.entityType()).isEqualTo("SALE");
    assertThat(event.source()).as("sem request HTTP o evento é de sistema").isEqualTo("SYSTEM");
    assertThat(event.productId()).isEqualTo(productId);
    assertThat(event.barcode()).isEqualTo(BARCODE);
    assertThat(event.quantity()).isEqualByComparingTo("2");
    assertThat(event.subtotal()).isEqualByComparingTo("19.80");
    assertThat(event.total()).isEqualByComparingTo("19.80");
    assertThat(event.itemCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("mesmo produto de novo soma a quantidade numa linha só, sem duplicar o item")
  void sumsRepeatedProductInSingleLine() throws SQLException {
    Sale sale = openScenario("Café 500g", "UN", "18.90");

    useCase.execute(
        new AddSaleItemCommand(sale.id(), registerId, BARCODE, null, new BigDecimal("2")));
    Sale updated =
        useCase.execute(
            new AddSaleItemCommand(sale.id(), registerId, null, productId, new BigDecimal("3.5")));

    assertThat(updated.items()).as("uma linha por produto, não duas").hasSize(1);
    assertThat(updated.items().getFirst().quantity()).isEqualByComparingTo("5.500");
    assertThat(updated.subtotal()).isEqualByComparingTo("103.95");
    assertThat(updated.total()).isEqualByComparingTo("103.95");
    assertThat(updated.itemCount()).isEqualTo(1);

    List<ItemRow> items = itemRows(sale.id());
    assertThat(items).hasSize(1);
    assertThat(items.getFirst().lineNumber()).isEqualTo(1);
    assertThat(items.getFirst().name())
        .as("BR-01: snapshot da primeira inclusão")
        .isEqualTo("Café 500g");
    assertThat(items.getFirst().quantity()).isEqualTo("5.500");
    assertThat(items.getFirst().lineTotal()).isEqualTo("103.95");

    SaleRow stored = saleRow(sale.id());
    assertThat(stored.itemCount()).isEqualTo(1);
    assertThat(stored.subtotal()).isEqualTo("103.95");
    assertThat(stored.total()).isEqualTo("103.95");
    assertThat(stored.version()).as("um update por inclusão").isEqualTo(2);
  }

  /** Operador, caixa aberto, venda e produto do cenário — venda pelo caminho real do passo 805. */
  private Sale openScenario(String name, String unit, String price) throws SQLException {
    userId = newOperator();
    registerId = cashRegisterId("CAIXA-01");
    cashSessionId =
        openCashSessionUseCase
            .execute(new OpenCashSessionCommand(registerId, new BigDecimal("100.00"), userId, null))
            .id();
    Sale sale = createSaleUseCase.execute(new CreateSaleCommand(registerId, userId));
    saleIds.add(sale.id());
    productId =
        callInOwnTransaction(
            () ->
                productStore.insert(
                    new NewProduct(
                        storeId(), name, BARCODE, null, null, unit, new BigDecimal(price), null)));
    return sale;
  }

  /** Operador de verdade: a FK de {@code sales.operator_user_id} exige um usuário. */
  private UUID newOperator() {
    return callInOwnTransaction(
        () -> userStore.insert(new NewUser(OPERATOR, "Operador de item", "hash", "ACTIVE")));
  }

  /** A série nasce na primeira alocação: nenhum teste pode herdar o contador do anterior. */
  @BeforeEach
  void resetSaleSequence() throws SQLException {
    deleteSaleSequence();
  }

  private void deleteSaleSequence() throws SQLException {
    execute(
        "delete from document_sequences where store_id = ? and doc_type = ?",
        storeId(),
        SALE_DOC_TYPE);
  }

  /** Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}. */
  @AfterEach
  void removeCommittedRows() throws SQLException {
    for (UUID saleId : saleIds) {
      execute("delete from sale_items where sale_id = ?", saleId);
      execute("delete from sales where id = ?", saleId);
    }
    for (UUID saleId : saleIds) {
      execute("delete from audit_events where entity_id = ?", saleId);
    }
    execute("delete from audit_events where entity_id = ?", cashSessionId);
    deleteSaleSequence();
    execute("delete from cash_movements where cash_session_id = ?", cashSessionId);
    execute("delete from cash_sessions where id = ?", cashSessionId);
    execute("delete from users where id = ?", userId);
    execute("delete from products where id = ?", productId);
  }

  /** Itens da venda na ordem de {@code line_number}, como o banco os guardou. */
  private List<ItemRow> itemRows(UUID saleId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select line_number, product_id, barcode_snapshot, name_snapshot, unit_snapshot,"
                    + " unit_price::text as unit_price, quantity::text as quantity,"
                    + " discount_amount::text as discount_amount, line_total::text as line_total"
                    + " from sale_items where sale_id = ? order by line_number")) {
      statement.setObject(1, saleId);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<ItemRow> rows = new ArrayList<>();
        while (resultSet.next()) {
          rows.add(
              new ItemRow(
                  resultSet.getInt("line_number"),
                  resultSet.getObject("product_id", UUID.class),
                  resultSet.getString("barcode_snapshot"),
                  resultSet.getString("name_snapshot"),
                  resultSet.getString("unit_snapshot"),
                  resultSet.getString("unit_price"),
                  resultSet.getString("quantity"),
                  resultSet.getString("discount_amount"),
                  resultSet.getString("line_total")));
        }
        return rows;
      }
    }
  }

  /** Cabeçalho da venda como o banco o guardou. */
  private SaleRow saleRow(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select subtotal::text as subtotal, discount_amount::text as discount_amount,"
                    + " total::text as total, item_count, version from sales where id = ?")) {
      statement.setObject(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("venda %s gravada", id).isTrue();
        return new SaleRow(
            resultSet.getString("subtotal"),
            resultSet.getString("discount_amount"),
            resultSet.getString("total"),
            resultSet.getInt("item_count"),
            resultSet.getLong("version"));
      }
    }
  }

  /** Evento de auditoria da venda; {@code details} vem extraído por chave do jsonb. */
  private Event eventOf(UUID entityId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select action, entity_type, source, (details->>'productId')::uuid as product_id,"
                    + " details->>'barcode' as barcode, (details->>'quantity')::numeric as quantity,"
                    + " (details->>'subtotal')::numeric as subtotal,"
                    + " (details->>'total')::numeric as total,"
                    + " (details->>'itemCount')::int as item_count"
                    + " from audit_events where entity_id = ? and action = 'SALE_ITEM_ADDED'")) {
      statement.setObject(1, entityId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("evento gravado para a venda %s", entityId).isTrue();
        return new Event(
            resultSet.getString("action"),
            resultSet.getString("entity_type"),
            resultSet.getString("source"),
            resultSet.getObject("product_id", UUID.class),
            resultSet.getString("barcode"),
            resultSet.getBigDecimal("quantity"),
            resultSet.getBigDecimal("subtotal"),
            resultSet.getBigDecimal("total"),
            resultSet.getInt("item_count"));
      }
    }
  }

  /** Id da loja configurada: a porta {@code StoreLookup} devolve o id desde o passo 204a. */
  private UUID storeId() {
    if (storeId == null) {
      storeId =
          callInOwnTransaction(
              () ->
                  storeLookup
                      .findByCode(defaultStoreCode)
                      .orElseThrow(() -> new IllegalStateException("loja do seed da V1 ausente"))
                      .id());
    }
    return storeId;
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      statement.executeUpdate();
    }
  }

  /** Roda a tarefa em transação própria, com o request context do Arc ativado na thread. */
  private static <T> T callInOwnTransaction(Callable<T> work) {
    ManagedContext requestContext = Arc.container().requestContext();
    boolean activated = !requestContext.isActive();
    if (activated) {
      requestContext.activate();
    }
    try {
      return QuarkusTransaction.requiringNew().call(work);
    } finally {
      if (activated) {
        requestContext.terminate();
      }
    }
  }

  /** Linha de {@code sale_items} como o banco a guardou. */
  private record ItemRow(
      int lineNumber,
      UUID productId,
      String barcode,
      String name,
      String unit,
      String unitPrice,
      String quantity,
      String discountAmount,
      String lineTotal) {}

  /** Cabeçalho de {@code sales} como o banco o guardou. */
  private record SaleRow(
      String subtotal, String discountAmount, String total, int itemCount, long version) {}

  /** Linha de {@code audit_events} como o banco a guardou. */
  private record Event(
      String action,
      String entityType,
      String source,
      UUID productId,
      String barcode,
      BigDecimal quantity,
      BigDecimal subtotal,
      BigDecimal total,
      int itemCount) {}
}
