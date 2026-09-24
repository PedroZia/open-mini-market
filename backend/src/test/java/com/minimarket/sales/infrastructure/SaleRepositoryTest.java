package com.minimarket.sales.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.f4b6a3.uuid.UuidCreator;
import com.minimarket.IntegrationTestBase;
import com.minimarket.sales.application.SaleSummary;
import com.minimarket.sales.domain.DiscountType;
import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleItem;
import com.minimarket.sales.domain.SaleStatus;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link SaleRepository} contra PostgreSQL real (Dev Services): round-trip do
 * agregado (itens, snapshots, quantidades, desconto e totais), sincronização de itens no update,
 * busca por período/status/sessão/operador com contagem e lock. Critério de aceite do passo 803.
 *
 * <p>As fixtures (loja MATRIZ e CAIXA-01 dos seeds, usuários, produtos e sessões de caixa) são
 * criadas por SQL e comitadas no {@code @BeforeEach}, como no {@code SalesMigrationTest}; os testes
 * rodam em transação que comita no fim ({@code @Transactional}) e o {@code @AfterEach} limpa o que
 * ficou, na ordem das FKs: {@code sale_items} → {@code sales} → {@code cash_sessions} → {@code
 * products} → {@code users}. A exceção é o teste do lock otimista: o flush que estoura marca a
 * transação para rollback, então ele roda com {@code @TestTransaction} — mesma escolha do {@code
 * ProductRepositoryTest.translatesStaleVersionOnUpdate}.
 */
@QuarkusTest
class SaleRepositoryTest extends IntegrationTestBase {

  private static final Instant DAY_ONE = Instant.parse("2026-01-10T10:00:00Z");
  private static final Instant DAY_TWO = Instant.parse("2026-01-11T10:00:00Z");
  private static final Instant DAY_THREE = Instant.parse("2026-01-12T10:00:00Z");
  private static final String NOTES = "venda de teste";

  @Inject SaleRepository saleRepository;

  @Inject EntityManager entityManager;

  private UUID storeId;
  private UUID registerId;
  private UUID operatorA;
  private UUID operatorB;
  private UUID openSessionId;
  private UUID closedSessionId;
  private UUID rice;
  private UUID beans;
  private UUID coffee;
  private UUID banana;
  private UUID customerId;
  private final List<UUID> saleIds = new ArrayList<>();
  private final List<UUID> productIds = new ArrayList<>();
  private final List<UUID> customerIds = new ArrayList<>();
  private final List<UUID> userIds = new ArrayList<>();

  @BeforeEach
  void prepareFixtures() throws SQLException {
    storeId = idByCode("stores", "MATRIZ");
    registerId = cashRegisterId("CAIXA-01");
    operatorA = createUser("vendas.repo.a");
    operatorB = createUser("vendas.repo.b");
    openSessionId = openSession(operatorA);
    closedSessionId = closedSession(operatorA);
    rice = createProduct("Arroz 5kg", "UN", "25.00");
    beans = createProduct("Feijão 1kg", "UN", "4.50");
    coffee = createProduct("Café 500g", "UN", "18.90");
    banana = createProduct("Banana prata", "KG", "6.99");
    customerId = createCustomer("Ana Souza");
  }

  @AfterEach
  void removeFixtures() throws SQLException {
    for (UUID saleId : saleIds) {
      execute("delete from sale_items where sale_id = ?", saleId);
      execute("delete from sales where id = ?", saleId);
    }
    deleteIfPresent("cash_sessions", openSessionId);
    deleteIfPresent("cash_sessions", closedSessionId);
    for (UUID productId : productIds) {
      execute("delete from products where id = ?", productId);
    }
    for (UUID customerId : customerIds) {
      execute("delete from customers where id = ?", customerId);
    }
    for (UUID userId : userIds) {
      execute("delete from users where id = ?", userId);
    }
  }

  @Test
  @Transactional
  @DisplayName("insert grava a venda e os itens (UUIDv7) e findById devolve o agregado completo")
  void insertsAndFindsById() {
    Sale sale = openSale(1, openSessionId, operatorA, DAY_ONE);
    sale.addItem(
        rice, "7891000315507", "Arroz 5kg", "UN", new BigDecimal("25.00"), new BigDecimal("2"));
    sale.addItem(beans, null, "Feijão 1kg", "UN", new BigDecimal("4.50"), new BigDecimal("1.005"));
    saleRepository.insert(sale);
    flushAndClear();

    assertThat(sale.id().version()).as("id da venda é UUIDv7").isEqualTo(7);
    assertThat(saleRepository.findById(sale.id()))
        .hasValueSatisfying(
            found -> {
              assertThat(found.id()).isEqualTo(sale.id());
              assertThat(found.storeId()).isEqualTo(storeId);
              assertThat(found.number()).isEqualTo(1L);
              assertThat(found.cashSessionId()).isEqualTo(openSessionId);
              assertThat(found.cashRegisterId()).isEqualTo(registerId);
              assertThat(found.operatorUserId()).isEqualTo(operatorA);
              assertThat(found.notes()).isEqualTo(NOTES);
              assertThat(found.createdAt()).isEqualTo(DAY_ONE);
              assertThat(found.status()).isEqualTo(SaleStatus.OPEN);
              assertThat(found.customerId()).isNull();
              assertThat(found.completedAt()).isNull();
              assertThat(found.itemCount()).isEqualTo(2);
              assertThat(found.subtotal()).isEqualByComparingTo("54.52");
              assertThat(found.discountAmount()).isEqualByComparingTo("0.00");
              assertThat(found.total()).isEqualByComparingTo("54.52");
              assertThat(found.items())
                  .satisfiesExactly(
                      item -> {
                        assertThat(item.productId()).isEqualTo(rice);
                        assertThat(item.barcode()).isEqualTo("7891000315507");
                        assertThat(item.name()).isEqualTo("Arroz 5kg");
                        assertThat(item.unit()).isEqualTo("UN");
                        assertThat(item.unitPrice()).isEqualByComparingTo("25.00");
                        assertThat(item.quantity()).isEqualByComparingTo("2.000");
                        assertThat(item.lineTotal()).isEqualByComparingTo("50.00");
                      },
                      item -> {
                        assertThat(item.productId()).isEqualTo(beans);
                        assertThat(item.barcode()).as("snapshot sem código de barras").isNull();
                        assertThat(item.quantity()).isEqualByComparingTo("1.005");
                        assertThat(item.lineTotal())
                            .as("1.005 × 4.50 HALF_UP")
                            .isEqualByComparingTo("4.52");
                      });
            });

    assertThat(saleItemIds(sale.id()))
        .as("id de cada item é UUIDv7, como nos outros repositórios")
        .isNotEmpty()
        .allSatisfy(id -> assertThat(id.version()).isEqualTo(7));
    assertThat(lineNumbers(sale.id())).containsExactly(1, 2);
    assertThat(moneyColumn("paid_amount", sale.id())).isEqualByComparingTo("0.00");
    assertThat(moneyColumn("change_amount", sale.id())).isEqualByComparingTo("0.00");
    assertThat(moneyColumn("discount_amount", sale.id())).isEqualByComparingTo("0.00");
    assertThat(saleColumn("item_count", sale.id())).isEqualTo(2);
    assertThat(saleColumn("version", sale.id())).isEqualTo(0L);
    assertThat(saleColumn("customer_id", sale.id())).as("cliente é do passo 811").isNull();
    assertThat(saleColumn("cancelled_at", sale.id())).isNull();
    assertThat(saleColumn("cancel_reason", sale.id())).isNull();

    assertThat(saleRepository.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  @Transactional
  @DisplayName("venda sem itens é válida e volta com os totais zerados")
  void insertsSaleWithoutItems() {
    Sale sale = openSale(2, openSessionId, operatorA, DAY_TWO);
    saleRepository.insert(sale);
    flushAndClear();

    assertThat(saleRepository.findById(sale.id()))
        .hasValueSatisfying(
            found -> {
              assertThat(found.items()).isEmpty();
              assertThat(found.itemCount()).isZero();
              assertThat(found.subtotal()).isEqualByComparingTo("0.00");
              assertThat(found.total()).isEqualByComparingTo("0.00");
            });
    assertThat(saleColumn("item_count", sale.id())).isEqualTo(0);
  }

  @Test
  @Transactional
  @DisplayName("round-trip preserva desconto percentual, totais e a conclusão da venda")
  void roundTripsDiscountAndCompletion() {
    Sale sale = openSale(1, openSessionId, operatorA, DAY_ONE);
    sale.addItem(
        rice, "7891000315507", "Arroz 5kg", "UN", new BigDecimal("25.00"), new BigDecimal("2"));
    sale.addItem(beans, null, "Feijão 1kg", "UN", new BigDecimal("4.50"), BigDecimal.ONE);
    saleRepository.insert(sale);
    flushAndClear();

    Instant completedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Sale loaded = saleRepository.findById(sale.id()).orElseThrow();
    loaded.applyDiscount(DiscountType.PERCENT, new BigDecimal("10"), "cliente do bairro");
    loaded.complete(completedAt);
    saleRepository.update(loaded);
    flushAndClear();

    assertThat(saleRepository.findById(sale.id()))
        .hasValueSatisfying(
            found -> {
              assertThat(found.status()).isEqualTo(SaleStatus.COMPLETED);
              assertThat(found.completedAt()).isEqualTo(completedAt);
              assertThat(found.discountType()).isEqualTo(DiscountType.PERCENT);
              assertThat(found.discountValue()).isEqualByComparingTo("10.00");
              assertThat(found.discountReason()).isEqualTo("cliente do bairro");
              assertThat(found.subtotal()).isEqualByComparingTo("54.50");
              assertThat(found.discountAmount()).as("10% de 54.50").isEqualByComparingTo("5.45");
              assertThat(found.total()).isEqualByComparingTo("49.05");
              assertThat(found.items()).hasSize(2);
            });
    assertThat(saleColumn("discount_type", sale.id())).isEqualTo("PERCENT");
    assertThat(moneyColumn("discount_value", sale.id())).isEqualByComparingTo("10.00");
    assertThat(saleColumn("discount_reason", sale.id())).isEqualTo("cliente do bairro");
    assertThat(saleColumn("completed_at", sale.id())).isNotNull();
  }

  @Test
  @Transactional
  @DisplayName(
      "update muda a quantidade, apaga o item removido do banco e insere o novo com posições compactas")
  void updateSyncsItems() {
    Sale sale = openSale(1, openSessionId, operatorA, DAY_ONE);
    sale.addItem(
        rice, "7891000315507", "Arroz 5kg", "UN", new BigDecimal("25.00"), new BigDecimal("1"));
    sale.addItem(
        beans, "7891000315508", "Feijão 1kg", "UN", new BigDecimal("4.50"), BigDecimal.ONE);
    sale.addItem(
        coffee, "7891000315509", "Café 500g", "UN", new BigDecimal("18.90"), new BigDecimal("3"));
    saleRepository.insert(sale);
    flushAndClear();

    Sale loaded = saleRepository.findById(sale.id()).orElseThrow();
    loaded.changeQuantity(rice, new BigDecimal("3"));
    loaded.removeItem(beans);
    loaded.addItem(
        banana, null, "Banana prata", "KG", new BigDecimal("6.99"), new BigDecimal("1.5"));
    saleRepository.update(loaded);
    flushAndClear();

    assertThat(saleRepository.findById(sale.id()))
        .hasValueSatisfying(
            found -> {
              assertThat(found.items())
                  .extracting(SaleItem::productId)
                  .as("Feijão saiu; Banana entrou no fim")
                  .containsExactly(rice, coffee, banana);
              assertThat(found.items().get(0).quantity()).isEqualByComparingTo("3.000");
              assertThat(found.items().get(0).lineTotal()).isEqualByComparingTo("75.00");
              assertThat(found.items().get(1).lineTotal()).isEqualByComparingTo("56.70");
              assertThat(found.items().get(2).quantity()).isEqualByComparingTo("1.500");
              assertThat(found.items().get(2).lineTotal())
                  .as("1.5 × 6.99")
                  .isEqualByComparingTo("10.49");
              assertThat(found.itemCount()).isEqualTo(3);
              assertThat(found.subtotal()).isEqualByComparingTo("142.19");
              assertThat(found.total()).isEqualByComparingTo("142.19");
            });
    assertThat(lineNumbers(sale.id()))
        .as("a posição compacta depois da remoção do segundo item")
        .containsExactly(1, 2, 3);
    assertThat(itemCountOf(sale.id())).as("linha do Feijão saiu do banco").isEqualTo(3);
    assertThat(productsOf(sale.id())).containsExactly(rice, coffee, banana);
    assertThat(saleColumn("item_count", sale.id())).isEqualTo(3);
  }

  @Test
  @Transactional
  @DisplayName("update repõe no fim o produto removido e readicionado, sem colisão de posição")
  void updateReinsertsRemovedProduct() {
    Sale sale = openSale(1, openSessionId, operatorA, DAY_ONE);
    sale.addItem(rice, "7891000315507", "Arroz 5kg", "UN", new BigDecimal("25.00"), BigDecimal.ONE);
    sale.addItem(
        beans, "7891000315508", "Feijão 1kg", "UN", new BigDecimal("4.50"), BigDecimal.ONE);
    sale.addItem(
        coffee, "7891000315509", "Café 500g", "UN", new BigDecimal("18.90"), BigDecimal.ONE);
    saleRepository.insert(sale);
    flushAndClear();

    Sale loaded = saleRepository.findById(sale.id()).orElseThrow();
    loaded.removeItem(beans);
    loaded.addItem(
        beans, "7891000315508", "Feijão 1kg", "UN", new BigDecimal("5.10"), new BigDecimal("2"));
    saleRepository.update(loaded);
    flushAndClear();

    assertThat(saleRepository.findById(sale.id()))
        .hasValueSatisfying(
            found -> {
              assertThat(found.items())
                  .extracting(SaleItem::productId)
                  .containsExactly(rice, coffee, beans);
              assertThat(found.items().get(2).unitPrice())
                  .as("inclusão nova usa o snapshot novo (BR-01)")
                  .isEqualByComparingTo("5.10");
              assertThat(found.items().get(2).quantity()).isEqualByComparingTo("2.000");
              assertThat(found.subtotal()).isEqualByComparingTo("54.10");
            });
    assertThat(lineNumbers(sale.id())).containsExactly(1, 2, 3);
    assertThat(itemCountOf(sale.id())).isEqualTo(3);
  }

  @Test
  @Transactional
  @DisplayName("linkCustomer grava customer_id e o round-trip restaura o cliente vinculado")
  void linksCustomerAndRoundTrips() {
    Sale sale = openSale(1, openSessionId, operatorA, DAY_ONE);
    sale.addItem(rice, "7891000315507", "Arroz 5kg", "UN", new BigDecimal("25.00"), BigDecimal.ONE);
    sale.linkCustomer(customerId);
    saleRepository.insert(sale);
    flushAndClear();

    assertThat(saleRepository.findById(sale.id()))
        .hasValueSatisfying(
            found -> {
              assertThat(found.customerId()).isEqualTo(customerId);
              assertThat(found.items()).hasSize(1);
            });
    assertThat(saleColumn("customer_id", sale.id())).isEqualTo(customerId);
    assertThat(saleColumn("version", sale.id())).as("o vínculo é estado da venda").isEqualTo(0L);

    Sale loaded = saleRepository.findById(sale.id()).orElseThrow();
    loaded.unlinkCustomer();
    saleRepository.update(loaded);
    flushAndClear();

    assertThat(saleRepository.findById(sale.id()))
        .hasValueSatisfying(found -> assertThat(found.customerId()).isNull());
    assertThat(saleColumn("customer_id", sale.id())).as("a coluna volta a nulo").isNull();
  }

  @Test
  @Transactional
  @DisplayName("venda concluída com cliente rehidrata o vínculo antes da conclusão")
  void roundTripsCustomerOfCompletedSale() {
    Sale sale = openSale(1, openSessionId, operatorA, DAY_ONE);
    sale.addItem(rice, "7891000315507", "Arroz 5kg", "UN", new BigDecimal("25.00"), BigDecimal.ONE);
    sale.linkCustomer(customerId);
    sale.complete(DAY_ONE.plusSeconds(30));
    saleRepository.insert(sale);
    flushAndClear();

    assertThat(saleRepository.findById(sale.id()))
        .hasValueSatisfying(
            found -> {
              assertThat(found.status()).isEqualTo(SaleStatus.COMPLETED);
              assertThat(found.customerId())
                  .as("o mapper vincula o cliente antes de concluir a venda")
                  .isEqualTo(customerId);
              assertThat(found.completedAt()).isEqualTo(DAY_ONE.plusSeconds(30));
            });
  }

  @Test
  @Transactional
  @DisplayName("search filtra por período, status, sessão e operador, pagina e o count acompanha")
  void searchesAndCounts() {
    UUID openA = insertSale(1, openSessionId, operatorA, DAY_ONE, null);
    UUID completedA = insertSale(2, openSessionId, operatorA, DAY_TWO, DAY_TWO.plusSeconds(60));
    UUID openB = insertSale(3, closedSessionId, operatorB, DAY_THREE, null);

    assertThat(ids(search(null, null, null, null, null, 0, 50)))
        .as("mais nova primeiro")
        .containsExactly(openB, completedA, openA);
    assertThat(ids(search(DAY_TWO, null, null, null, null, 0, 50)))
        .as("from inclusivo")
        .containsExactly(openB, completedA);
    assertThat(ids(search(null, DAY_THREE, null, null, null, 0, 50)))
        .as("to exclusivo")
        .containsExactly(completedA, openA);
    assertThat(ids(search(DAY_TWO, DAY_THREE, null, null, null, 0, 50)))
        .containsExactly(completedA);
    assertThat(ids(search(null, null, SaleStatus.OPEN, null, null, 0, 50)))
        .containsExactly(openB, openA);
    assertThat(ids(search(null, null, SaleStatus.COMPLETED, null, null, 0, 50)))
        .containsExactly(completedA);
    assertThat(ids(search(null, null, null, openSessionId, null, 0, 50)))
        .containsExactly(completedA, openA);
    assertThat(ids(search(null, null, null, null, operatorB, 0, 50))).containsExactly(openB);
    assertThat(ids(search(null, null, SaleStatus.OPEN, openSessionId, operatorA, 0, 50)))
        .containsExactly(openA);

    assertThat(ids(search(null, null, null, null, null, 0, 2))).containsExactly(openB, completedA);
    assertThat(ids(search(null, null, null, null, null, 1, 2))).containsExactly(openA);
    assertThat(search(null, null, null, null, null, 5, 2)).isEmpty();

    assertThat(saleRepository.count(null, null, null, null, null)).isEqualTo(3);
    assertThat(saleRepository.count(DAY_TWO, null, null, null, null)).isEqualTo(2);
    assertThat(saleRepository.count(null, DAY_THREE, null, null, null)).isEqualTo(2);
    assertThat(saleRepository.count(null, null, SaleStatus.OPEN, null, null)).isEqualTo(2);
    assertThat(saleRepository.count(null, null, null, openSessionId, null)).isEqualTo(2);
    assertThat(saleRepository.count(null, null, null, null, operatorB)).isEqualTo(1);
    assertThat(saleRepository.count(null, null, SaleStatus.OPEN, openSessionId, operatorA))
        .isEqualTo(1);

    assertThat(search(null, null, null, null, null, 0, 50).getFirst())
        .satisfies(
            summary -> {
              assertThat(summary.id()).isEqualTo(openB);
              assertThat(summary.storeId()).isEqualTo(storeId);
              assertThat(summary.number()).isEqualTo(3L);
              assertThat(summary.status()).isEqualTo(SaleStatus.OPEN);
              assertThat(summary.cashSessionId()).isEqualTo(closedSessionId);
              assertThat(summary.cashRegisterId()).isEqualTo(registerId);
              assertThat(summary.operatorUserId()).isEqualTo(operatorB);
              assertThat(summary.customerId()).isNull();
              assertThat(summary.subtotal()).isEqualByComparingTo("25.00");
              assertThat(summary.discountAmount()).isEqualByComparingTo("0.00");
              assertThat(summary.total()).isEqualByComparingTo("25.00");
              assertThat(summary.paidAmount()).isEqualByComparingTo("0.00");
              assertThat(summary.changeAmount()).isEqualByComparingTo("0.00");
              assertThat(summary.itemCount()).isEqualTo(1);
              assertThat(summary.createdAt()).isEqualTo(DAY_THREE);
              assertThat(summary.completedAt()).isNull();
            });
    assertThat(summaryOf(completedA).completedAt()).isEqualTo(DAY_TWO.plusSeconds(60));
  }

  @Test
  @Transactional
  @DisplayName("existsOpenByCashSession só é verdadeiro com venda aberta na sessão")
  void checksOpenSaleByCashSession() {
    assertThat(saleRepository.existsOpenByCashSession(openSessionId)).isFalse();

    insertSale(1, openSessionId, operatorA, DAY_ONE, null);
    insertSale(2, closedSessionId, operatorA, DAY_ONE, DAY_ONE.plusSeconds(30));

    assertThat(saleRepository.existsOpenByCashSession(openSessionId)).isTrue();
    assertThat(saleRepository.existsOpenByCashSession(closedSessionId))
        .as("venda concluída não conta")
        .isFalse();
    assertThat(saleRepository.existsOpenByCashSession(UUID.randomUUID())).isFalse();
  }

  @Test
  @Transactional
  @DisplayName("lockById devolve o agregado com os itens e vazio para id desconhecido")
  void locksById() {
    Sale sale = openSale(1, openSessionId, operatorA, DAY_ONE);
    sale.addItem(
        rice, "7891000315507", "Arroz 5kg", "UN", new BigDecimal("25.00"), new BigDecimal("2"));
    sale.addItem(beans, null, "Feijão 1kg", "UN", new BigDecimal("4.50"), BigDecimal.ONE);
    saleRepository.insert(sale);
    flushAndClear();

    assertThat(saleRepository.lockById(sale.id()))
        .hasValueSatisfying(
            found -> {
              assertThat(found.id()).isEqualTo(sale.id());
              assertThat(found.items())
                  .extracting(SaleItem::productId)
                  .containsExactly(rice, beans);
              assertThat(found.subtotal()).isEqualByComparingTo("54.50");
            });
    assertThat(saleRepository.lockById(UUID.randomUUID())).isEmpty();
  }

  @Test
  @Transactional
  @DisplayName("update de venda concluída não reabre nem apaga o estado final")
  void updateKeepsCompletedState() {
    Sale sale = openSale(1, openSessionId, operatorA, DAY_ONE);
    sale.addItem(rice, "7891000315507", "Arroz 5kg", "UN", new BigDecimal("25.00"), BigDecimal.ONE);
    sale.complete(DAY_ONE.plusSeconds(10));
    saleRepository.insert(sale);
    flushAndClear();

    Sale loaded = saleRepository.findById(sale.id()).orElseThrow();
    saleRepository.update(loaded);
    flushAndClear();

    assertThat(saleRepository.findById(sale.id()))
        .hasValueSatisfying(
            found -> {
              assertThat(found.status()).isEqualTo(SaleStatus.COMPLETED);
              assertThat(found.completedAt()).isEqualTo(DAY_ONE.plusSeconds(10));
              assertThat(found.items()).hasSize(1);
            });
  }

  @Test
  @TestTransaction
  @DisplayName(
      "update de venda alterada por outra transação vira ConflictException(CONCURRENT_MODIFICATION)")
  void translatesStaleVersionOnUpdate() {
    Sale sale = openSale(1, openSessionId, operatorA, DAY_ONE);
    sale.addItem(rice, "7891000315507", "Arroz 5kg", "UN", new BigDecimal("25.00"), BigDecimal.ONE);
    saleRepository.insert(sale);
    flushAndClear();
    // A entidade entra no contexto com a versão 0 — é ela que o caso de uso leu.
    Sale loaded = saleRepository.findById(sale.id()).orElseThrow();
    // Outra transação grava primeiro, direto no banco: o update com "where version = 0" não acha
    // mais a linha e o flush antecipado traduz o stale do Hibernate — o backstop do lock otimista.
    entityManager
        .createNativeQuery("update sales set version = version + 1 where id = :id")
        .setParameter("id", sale.id())
        .executeUpdate();
    loaded.changeQuantity(rice, new BigDecimal("2"));

    assertThatThrownBy(() -> saleRepository.update(loaded))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
  }

  @Test
  @Transactional
  @DisplayName("venda cancelada no banco falha explícito em vez de virar venda aberta")
  void failsExplicitlyForCancelledSale() {
    Sale sale = openSale(1, openSessionId, operatorA, DAY_ONE);
    sale.addItem(rice, "7891000315507", "Arroz 5kg", "UN", new BigDecimal("25.00"), BigDecimal.ONE);
    saleRepository.insert(sale);
    flushAndClear();
    entityManager
        .createNativeQuery(
            "update sales set status = 'CANCELLED', cancelled_at = now(), cancel_reason = 'desistiu'"
                + " where id = :id")
        .setParameter("id", sale.id())
        .executeUpdate();

    assertThatThrownBy(() -> saleRepository.findById(sale.id()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CANCELLED");
  }

  /** Venda nova no caixa da sessão informada; entra na lista de limpeza do teste. */
  private Sale openSale(long number, UUID cashSessionId, UUID operatorId, Instant createdAt) {
    Sale sale =
        new Sale(
            UuidCreator.getTimeOrderedEpoch(),
            storeId,
            number,
            cashSessionId,
            registerId,
            operatorId,
            NOTES,
            createdAt);
    saleIds.add(sale.id());
    return sale;
  }

  /** Venda com um item de arroz, opcionalmente concluída, gravada e fora do contexto. */
  private UUID insertSale(
      long number, UUID cashSessionId, UUID operatorId, Instant createdAt, Instant completedAt) {
    Sale sale = openSale(number, cashSessionId, operatorId, createdAt);
    sale.addItem(rice, "7891000315507", "Arroz 5kg", "UN", new BigDecimal("25.00"), BigDecimal.ONE);
    if (completedAt != null) {
      sale.complete(completedAt);
    }
    saleRepository.insert(sale);
    flushAndClear();
    return sale.id();
  }

  private List<SaleSummary> search(
      Instant from,
      Instant to,
      SaleStatus status,
      UUID cashSessionId,
      UUID operatorUserId,
      int page,
      int size) {
    return saleRepository.search(from, to, status, cashSessionId, operatorUserId, page, size);
  }

  private static List<UUID> ids(List<SaleSummary> sales) {
    return sales.stream().map(SaleSummary::id).toList();
  }

  private SaleSummary summaryOf(UUID saleId) {
    return search(null, null, null, null, null, 0, 50).stream()
        .filter(summary -> summary.id().equals(saleId))
        .findFirst()
        .orElseThrow();
  }

  /** Grava o que o repositório tem no contexto e limpa: o que o teste lê depois vem do banco. */
  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }

  private List<Integer> lineNumbers(UUID saleId) {
    return entityManager
        .createQuery(
            "select i.lineNumber from SaleItemEntity i where i.saleId = :saleId"
                + " order by i.lineNumber",
            Integer.class)
        .setParameter("saleId", saleId)
        .getResultList();
  }

  private List<UUID> productsOf(UUID saleId) {
    return entityManager
        .createQuery(
            "select i.productId from SaleItemEntity i where i.saleId = :saleId order by i.lineNumber",
            UUID.class)
        .setParameter("saleId", saleId)
        .getResultList();
  }

  private List<UUID> saleItemIds(UUID saleId) {
    return entityManager
        .createQuery("select i.id from SaleItemEntity i where i.saleId = :saleId", UUID.class)
        .setParameter("saleId", saleId)
        .getResultList();
  }

  private int itemCountOf(UUID saleId) {
    return entityManager
        .createQuery("select count(i) from SaleItemEntity i where i.saleId = :saleId", Long.class)
        .setParameter("saleId", saleId)
        .getSingleResult()
        .intValue();
  }

  /** Coluna crua da linha da venda: confere o que o mapper escreveu (e o que não escreveu). */
  private Object saleColumn(String column, UUID saleId) {
    return entityManager
        .createNativeQuery("select " + column + " from sales where id = :id")
        .setParameter("id", saleId)
        .getSingleResult();
  }

  /** Coluna monetária crua da venda (numeric(14,2)). */
  private BigDecimal moneyColumn(String column, UUID saleId) {
    return (BigDecimal) saleColumn(column, saleId);
  }

  private UUID idByCode(String table, String code) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select id from " + table + " where code = ?")) {
      statement.setString(1, code);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("seed de %s (%s) presente", table, code).isTrue();
        return resultSet.getObject(1, UUID.class);
      }
    }
  }

  private UUID createUser(String username) throws SQLException {
    UUID id =
        insertReturningId(
            "insert into users (id, username, password_hash, display_name, status)"
                + " values (uuidv7(), ?, 'hash', 'Operador de venda', 'ACTIVE') returning id",
            username);
    userIds.add(id);
    return id;
  }

  private UUID createProduct(String name, String unit, String price) throws SQLException {
    UUID id =
        insertReturningId(
            "insert into products (id, store_id, name, unit, price)"
                + " values (uuidv7(), ?, ?, ?, ?) returning id",
            storeId,
            name,
            unit,
            new BigDecimal(price));
    productIds.add(id);
    return id;
  }

  /** Cliente do cenário do vínculo (passo 811): ativo, como o default da tabela. */
  private UUID createCustomer(String name) throws SQLException {
    UUID id =
        insertReturningId(
            "insert into customers (id, store_id, name) values (uuidv7(), ?, ?) returning id",
            storeId,
            name);
    customerIds.add(id);
    return id;
  }

  private UUID openSession(UUID operatorId) throws SQLException {
    return insertReturningId(
        "insert into cash_sessions (id, store_id, cash_register_id, status, opened_by_user_id,"
            + " opened_at, opening_amount)"
            + " values (uuidv7(), ?, ?, 'OPEN', ?, now(), 100.00) returning id",
        storeId,
        registerId,
        operatorId);
  }

  /** Sessão fechada da mesma sessão/loja: o filtro por sessão e o histórico contam com ela. */
  private UUID closedSession(UUID operatorId) throws SQLException {
    return insertReturningId(
        "insert into cash_sessions (id, store_id, cash_register_id, status, opened_by_user_id,"
            + " opened_at, opening_amount, closed_by_user_id, closed_at, counted_amount,"
            + " expected_amount, difference_amount, closing_notes)"
            + " values (uuidv7(), ?, ?, 'CLOSED', ?, now(), 100.00, ?, now(), 100.00, 100.00, 0.00,"
            + " 'turno encerrado') returning id",
        storeId,
        registerId,
        operatorId,
        operatorId);
  }

  private UUID insertReturningId(String sql, Object... parameters) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("insert com returning devolve o id").isTrue();
        return resultSet.getObject(1, UUID.class);
      }
    }
  }

  private void deleteIfPresent(String table, UUID id) throws SQLException {
    if (id != null) {
      execute("delete from " + table + " where id = ?", id);
    }
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
}
