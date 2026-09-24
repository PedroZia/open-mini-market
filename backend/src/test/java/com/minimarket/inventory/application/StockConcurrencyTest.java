package com.minimarket.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.inventory.domain.StockMovementType;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Concorrência do {@link StockService} contra PostgreSQL real (Dev Services): as garantias do §8
 * sob disputa de verdade, fechando a Fase 7 (passo 707).
 *
 * <p>Cada worker roda em thread própria, com o request context do Arc ativado e transação própria
 * ({@code QuarkusTransaction.requiringNew}), e o {@link CountDownLatch} solta todos no mesmo
 * instante — sem {@code sleep} e sem {@code @Disabled}. O que o teste prova são as invariantes do
 * banco — o saldo materializado, o encadeamento de {@code balance_after} do ledger e a ausência de
 * deadlock —, nunca a ordem em que as threads chegaram: ordem é do escalonador, efeito é do lock.
 *
 * <p>Sem {@code @TestTransaction}: os workers comitam de verdade, a thread principal não segura
 * conexão enquanto eles rodam (os 20 do primeiro cenário cabem no pool default do Quarkus) e o
 * {@code @AfterEach} limpa por SQL o que comitou, na ordem das FKs: movimentos → saldos → produtos
 * → usuários — o banco é compartilhado com os demais testes do fork.
 */
@QuarkusTest
class StockConcurrencyTest extends IntegrationTestBase {

  /** Workers do dreno: um ajuste de −1 por worker sobre o saldo de 20. */
  private static final int ADJUSTMENT_WORKERS = 20;

  /** Saldo da fixture do dreno, escrito direto no saldo: fixture não é regra de negócio. */
  private static final String SEEDED_QUANTITY = "20.000";

  /** Rodadas do segundo cenário: cada uma repete a colisão das ordens invertidas. */
  private static final int INVERTED_ORDER_ROUNDS = 50;

  /** Espera máxima de cada worker: o teste falha, nunca trava, se a corrida não terminar. */
  private static final long WORKER_TIMEOUT_SECONDS = 30;

  private static final String OPERATOR_DRAIN = "estoque.concorrencia.dreno";

  private static final String OPERATOR_ORDER = "estoque.concorrencia.ordem";

  @Inject StockService stockService;

  @Inject ProductStockStore productStockStore;

  @Inject ProductStore productStore;

  @Inject UserStore userStore;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  private UUID storeId;

  private UUID userId;

  /** Fixtures comitadas pelos testes, para a limpeza na ordem das FKs. */
  private final List<UUID> productIds = new ArrayList<>();

  private final List<UUID> userIds = new ArrayList<>();

  @Test
  @DisplayName(
      "20 ajustes simultâneos de −1 em saldo 20: saldo final 0, 20 movimentos e cada saldo de 19 a 0 visto uma vez")
  void drainsBalanceWithConcurrentAdjustments() throws Exception {
    userId = newOperator(OPERATOR_DRAIN);
    UUID productId = newProduct("Produto do dreno concorrente");
    seedQuantity(productId, SEEDED_QUANTITY);

    ApplyStockMovementCommand debit = adjustment(productId, "-1.000");
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(ADJUSTMENT_WORKERS);
    List<AppliedStockMovement> applied = new ArrayList<>();
    try {
      List<Future<AppliedStockMovement>> futures =
          IntStream.range(0, ADJUSTMENT_WORKERS)
              .mapToObj(
                  index ->
                      workers.submit(
                          () -> {
                            start.await();
                            return inOwnTransaction(() -> stockService.applyMovement(debit));
                          }))
              .toList();
      start.countDown();
      for (Future<AppliedStockMovement> future : futures) {
        applied.add(await(future));
      }
    } finally {
      workers.shutdown();
      assertThat(workers.awaitTermination(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("workers terminaram")
          .isTrue();
    }

    List<Integer> zeroToNineteen = IntStream.range(0, ADJUSTMENT_WORKERS).boxed().toList();

    assertThat(applied.stream().map(AppliedStockMovement::movementId).toList())
        .as("cada worker aplicou um movimento distinto")
        .doesNotHaveDuplicates();
    assertThat(applied.stream().map(movement -> movement.balanceAfter().intValueExact()).toList())
        .as("cada worker aplicou sobre um saldo distinto: ninguém perdeu update")
        .containsExactlyInAnyOrderElementsOf(zeroToNineteen);
    assertThat(applied.stream().map(movement -> movement.balanceBefore().intValueExact()).toList())
        .as("o saldo antes de cada ajuste é o depois + 1: a leitura aconteceu sob o lock")
        .containsExactlyInAnyOrderElementsOf(
            IntStream.range(1, ADJUSTMENT_WORKERS + 1).boxed().toList());

    List<MovementRow> ledger = ledgerRows(productId);
    assertThat(ledger)
        .as("um movimento por ajuste: nem a mais, nem a menos")
        .hasSize(ADJUSTMENT_WORKERS);
    assertThat(ledger).extracting(MovementRow::type).containsOnly("ADJUSTMENT");
    assertThat(ledger).extracting(MovementRow::delta).containsOnly(-1);
    assertThat(ledger.stream().map(MovementRow::balanceAfter).toList())
        .as("nenhum saldo repetido e nenhum buraco: os 20 saldos de 19 a 0, cada um uma vez")
        .containsExactlyInAnyOrderElementsOf(zeroToNineteen);
    assertThat(ledger.stream().mapToInt(MovementRow::delta).sum())
        .as("a soma dos deltas é a variação do saldo: 20 ajustes de −1 = −20")
        .isEqualTo(-ADJUSTMENT_WORKERS);

    BigDecimal balance = quantity(productId);
    assertThat(new BigDecimal(SEEDED_QUANTITY).add(BigDecimal.valueOf(-ADJUSTMENT_WORKERS)))
        .as("saldo inicial (20) + soma dos deltas (−20) = saldo final")
        .isEqualByComparingTo(balance);
    assertThat(balance).as("o saldo final é 0").isEqualByComparingTo("0");
    assertThat(lastBalanceAfter(productId))
        .as("o saldo materializado bate com o balance_after do último movimento")
        .isEqualByComparingTo(balance);
  }

  @Test
  @DisplayName(
      "dois produtos em ordens invertidas não travam: o lote é aplicado na ordem canônica de product_id")
  void appliesInvertedOrdersWithoutDeadlock() throws Exception {
    userId = newOperator(OPERATOR_ORDER);
    UUID first = newProduct("Produto da ordem A");
    UUID second = newProduct("Produto da ordem B");
    seedQuantity(first, "0");
    seedQuantity(second, "0");

    List<ApplyStockMovementCommand> forward =
        List.of(adjustment(first, "1.000"), adjustment(second, "1.000"));
    List<ApplyStockMovementCommand> reversed =
        List.of(adjustment(second, "1.000"), adjustment(first, "1.000"));

    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      for (int round = 1; round <= INVERTED_ORDER_ROUNDS; round++) {
        CountDownLatch start = new CountDownLatch(1);
        Future<List<AppliedStockMovement>> forwardResult =
            workers.submit(
                () -> {
                  start.await();
                  return inOwnTransaction(() -> stockService.applyMovements(forward));
                });
        Future<List<AppliedStockMovement>> reversedResult =
            workers.submit(
                () -> {
                  start.await();
                  return inOwnTransaction(() -> stockService.applyMovements(reversed));
                });
        start.countDown();

        List<UUID> forwardOrder = productOrder(await(forwardResult));
        List<UUID> reversedOrder = productOrder(await(reversedResult));

        assertThat(reversedOrder)
            .as("rodada %d: as duas ordens de entrada viram a mesma ordem de lock", round)
            .isEqualTo(forwardOrder);
        assertThat(forwardOrder)
            .as("rodada %d: a ordem aplicada é crescente por product_id", round)
            .isSorted();
      }
    } finally {
      workers.shutdown();
      assertThat(workers.awaitTermination(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("workers terminaram")
          .isTrue();
    }

    int movementsPerProduct = 2 * INVERTED_ORDER_ROUNDS;
    for (UUID productId : List.of(first, second)) {
      List<MovementRow> ledger = ledgerRows(productId);

      assertThat(quantity(productId))
          .as("saldo do produto %s: 2 movimentos de +1 por rodada", productId)
          .isEqualByComparingTo(BigDecimal.valueOf(movementsPerProduct));
      assertThat(ledger)
          .as("saldo do produto %s aplicado %d vezes", productId, movementsPerProduct)
          .hasSize(movementsPerProduct);
      assertThat(ledger.stream().mapToInt(MovementRow::delta).sum())
          .as("a soma dos deltas é a variação do saldo do produto %s", productId)
          .isEqualTo(movementsPerProduct);
      assertThat(ledger.stream().map(MovementRow::balanceAfter).toList())
          .as(
              "a cadeia de saldos do produto %s avança um por movimento, sem buraco nem repetição",
              productId)
          .containsExactlyInAnyOrderElementsOf(
              IntStream.rangeClosed(1, movementsPerProduct).boxed().toList());
      assertThat(lastBalanceAfter(productId))
          .as("o saldo materializado bate com o balance_after do último movimento")
          .isEqualByComparingTo(BigDecimal.valueOf(movementsPerProduct));
    }
  }

  /** Ordem dos produtos no retorno do lote: é a ordem em que o serviço tocou nas linhas. */
  private static List<UUID> productOrder(List<AppliedStockMovement> applied) {
    return applied.stream().map(AppliedStockMovement::productId).toList();
  }

  /** Operador de verdade: o {@code created_by_user_id} do ledger tem FK para {@code users}. */
  private UUID newOperator(String username) {
    UUID id =
        inOwnTransaction(
            () -> userStore.insert(new NewUser(username, "Operador de estoque", "hash", "ACTIVE")));
    userIds.add(id);
    return id;
  }

  /** Produto vivo da loja, pela porta do catálogo. */
  private UUID newProduct(String name) {
    UUID id =
        inOwnTransaction(
            () ->
                productStore.insert(
                    new NewProduct(
                        storeId(), name, null, null, null, "UN", new BigDecimal("9.90"), null)));
    productIds.add(id);
    return id;
  }

  /**
   * Comita a linha de saldo do produto com o saldo informado e sem nenhum movimento: a fixture só
   * precisa da linha viva para os workers disputarem o lock — o ledger dos testes nasce dos
   * ajustes.
   */
  private void seedQuantity(UUID productId, String quantity) {
    inOwnTransaction(
        () -> {
          productStockStore.insertIfAbsent(storeId(), productId);
          ProductStockSummary stock =
              productStockStore.lockByProduct(storeId(), productId).orElseThrow();
          productStockStore.updateQuantity(stock.id(), new BigDecimal(quantity));
          return null;
        });
  }

  /** Ajuste do cenário: delta assinado na convenção do ledger, sem custo, referência ou motivo. */
  private ApplyStockMovementCommand adjustment(UUID productId, String quantityDelta) {
    return new ApplyStockMovementCommand(
        productId,
        StockMovementType.ADJUSTMENT,
        new BigDecimal(quantityDelta),
        null,
        null,
        null,
        null,
        userId);
  }

  /** Espera o worker e devolve o resultado; falha do worker vira a falha do teste, com a causa. */
  private static <T> T await(Future<T> worker) {
    try {
      return worker.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException timedOut) {
      worker.cancel(true);
      throw new AssertionError(
          "worker não terminou em %ds".formatted(WORKER_TIMEOUT_SECONDS), timedOut);
    } catch (ExecutionException failed) {
      throw new AssertionError("worker falhou: " + failed.getCause(), failed.getCause());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("teste interrompido esperando o worker", interrupted);
    }
  }

  /** Roda a tarefa em transação própria, com o request context do Arc ativado na thread. */
  private static <T> T inOwnTransaction(Callable<T> work) {
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

  /** Saldo materializado do produto, como o banco o guardou. */
  private BigDecimal quantity(UUID productId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select quantity::text from product_stocks where store_id = ? and product_id = ?")) {
      statement.setObject(1, storeId());
      statement.setObject(2, productId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next())
            .as("linha de saldo do produto %s presente", productId)
            .isTrue();
        return new BigDecimal(resultSet.getString(1));
      }
    }
  }

  /**
   * Movimentos do produto na ordem de aplicação (por {@code created_at} e id do UUIDv7), reduzidos
   * ao que as invariantes usam: as quantidades do cenário são inteiras, então o delta e o {@code
   * balance_after} viram {@code int} — é o multiconjunto que o teste compara.
   */
  private List<MovementRow> ledgerRows(UUID productId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select type, quantity_delta::text as delta, balance_after::text as balance_after"
                    + " from stock_movements where product_id = ? order by created_at, id")) {
      statement.setObject(1, productId);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<MovementRow> rows = new ArrayList<>();
        while (resultSet.next()) {
          rows.add(
              new MovementRow(
                  resultSet.getString("type"),
                  new BigDecimal(resultSet.getString("delta")).intValueExact(),
                  new BigDecimal(resultSet.getString("balance_after")).intValueExact()));
        }
        return rows;
      }
    }
  }

  /**
   * {@code balance_after} do último movimento aplicado ao produto. A ordem vem do id (UUIDv7), não
   * do {@code created_at}: o id é gerado dentro do lock e o lock só é solto no commit, então a
   * ordem dos ids do produto é exatamente a ordem em que o serviço tocou a linha — já o {@code
   * created_at} é lido do relógio <em>antes</em> do lock e pode inverter entre workers
   * concorrentes.
   */
  private BigDecimal lastBalanceAfter(UUID productId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select balance_after::text from stock_movements where product_id = ?"
                    + " order by id desc limit 1")) {
      statement.setObject(1, productId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next())
            .as("ledger do produto %s com pelo menos um movimento", productId)
            .isTrue();
        return new BigDecimal(resultSet.getString(1));
      }
    }
  }

  /** Remove o que os testes comitaram, na ordem que as FKs exigem — o banco é compartilhado. */
  @AfterEach
  void removeCommittedFixture() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      for (UUID productId : productIds) {
        execute(connection, "delete from stock_movements where product_id = ?", productId);
        execute(connection, "delete from product_stocks where product_id = ?", productId);
        execute(connection, "delete from products where id = ?", productId);
      }
      for (UUID userId : userIds) {
        execute(connection, "delete from users where id = ?", userId);
      }
    }
  }

  private static void execute(Connection connection, String sql, UUID id) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      statement.executeUpdate();
    }
  }

  /** Id da loja configurada: a porta {@code StoreLookup} devolve o id desde o passo 204a. */
  private UUID storeId() {
    if (storeId == null) {
      storeId =
          inOwnTransaction(
              () ->
                  storeLookup
                      .findByCode(defaultStoreCode)
                      .orElseThrow(() -> new IllegalStateException("loja do seed da V1 ausente"))
                      .id());
    }
    return storeId;
  }

  /** Linha do ledger reduzida às invariantes: tipo, delta e saldo inteiros. */
  private record MovementRow(String type, int delta, int balanceAfter) {}
}
