package com.minimarket.inventory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.minimarket.IntegrationTestBase;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.inventory.application.ProductStockSummary;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Concorrência do {@link ProductStockRepository#lockByProduct} contra PostgreSQL real (Dev
 * Services): o {@code SELECT ... FOR UPDATE} de uma transação bloqueia a outra na mesma linha até o
 * commit.
 *
 * <p>Sem {@code @TestTransaction}: cada worker roda em transação própria ({@code
 * QuarkusTransaction.requiringNew()}) com o request context do Arc ativado na thread, e a fixture
 * precisa estar comitada para as duas transações verem a linha. O teste limpa pelo SQL o que
 * comitou — o banco é compartilhado com os demais testes do fork e as FKs são {@code restrict}.
 *
 * <p>A prova é determinística, sem sleep: o latch garante que a segunda tentativa começa com a
 * primeira ainda segurando o lock; o teste espera o PostgreSQL reportar um backend esperando por
 * lock; aí libera a primeira e compara os {@code System.nanoTime()} — a aquisição da segunda só
 * pode ter acontecido depois da liberação da primeira.
 */
@QuarkusTest
class ProductStockLockTest extends IntegrationTestBase {

  private static final String OPERATOR = "estoque.lock";

  private static final String PRODUCT_NAME = "Produto do lock de estoque";

  private static final String SEEDED_QUANTITY = "7.000";

  @Inject ProductStockRepository stockRepository;

  @Inject ProductStore productStore;

  @Inject UserStore userStore;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  private UUID storeId;

  private UUID userId;

  private UUID productId;

  private UUID stockId;

  @Test
  @DisplayName(
      "lockByProduct serializa duas transações: a segunda só trava a linha depois da soltura")
  void serializesConcurrentLockers() throws Exception {
    UUID lockedProductId = seedCommittedStockRow();
    AtomicReference<ProductStockSummary> firstRead = new AtomicReference<>();
    AtomicReference<ProductStockSummary> secondRead = new AtomicReference<>();
    AtomicLong firstAcquired = new AtomicLong();
    AtomicLong firstReleased = new AtomicLong();
    AtomicLong secondAcquired = new AtomicLong();
    CountDownLatch firstLocked = new CountDownLatch(1);
    CountDownLatch secondAttempting = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);

    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<?> first =
          workers.submit(
              () ->
                  inOwnTransaction(
                      () -> {
                        firstRead.set(
                            stockRepository
                                .lockByProduct(storeId(), lockedProductId)
                                .orElseThrow());
                        firstAcquired.set(System.nanoTime());
                        firstLocked.countDown();
                        awaitLatch(releaseFirst);
                        firstReleased.set(System.nanoTime());
                      }));
      assertThat(firstLocked.await(10, TimeUnit.SECONDS))
          .as("primeira transação travou a linha do saldo")
          .isTrue();

      Future<?> second =
          workers.submit(
              () ->
                  inOwnTransaction(
                      () -> {
                        secondAttempting.countDown();
                        secondRead.set(
                            stockRepository
                                .lockByProduct(storeId(), lockedProductId)
                                .orElseThrow());
                        secondAcquired.set(System.nanoTime());
                      }));
      assertThat(secondAttempting.await(10, TimeUnit.SECONDS))
          .as("segunda transação começou a disputar a linha")
          .isTrue();
      awaitSecondTransactionWaitingForLock();

      releaseFirst.countDown();
      first.get(30, TimeUnit.SECONDS);
      second.get(30, TimeUnit.SECONDS);
    } finally {
      // Idempotente: se o teste falhou antes, nada fica preso no latch.
      releaseFirst.countDown();
      workers.shutdown();
      assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).as("workers terminaram").isTrue();
    }

    assertThat(firstAcquired.get()).as("primeira transação leu a linha").isPositive();
    assertThat(secondAcquired.get())
        .as("a segunda só adquiriu o lock depois que a primeira soltou")
        .isGreaterThanOrEqualTo(firstReleased.get());
    assertThat(firstRead.get().id()).as("as duas leram a mesma linha").isEqualTo(stockId);
    assertThat(secondRead.get().id()).isEqualTo(stockId);
    assertThat(firstRead.get().quantity()).isEqualByComparingTo(SEEDED_QUANTITY);
    assertThat(secondRead.get().quantity()).isEqualByComparingTo(SEEDED_QUANTITY);
    assertThat(secondRead.get().version())
        .as("o lock relê a linha sem gravar: a versão não muda")
        .isEqualTo(firstRead.get().version());
  }

  /**
   * Espera o PostgreSQL reportar um backend esperando por lock — é a segunda transação bloqueada no
   * {@code FOR UPDATE} da primeira. Bounded e sem sleep: falhar aqui é o teste do lock falhando.
   */
  private void awaitSecondTransactionWaitingForLock() throws SQLException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (waitingBackends() > 0) {
        return;
      }
    }
    fail("a segunda transação não ficou esperando pelo lock do saldo");
  }

  /**
   * Backends do cluster esperando por lock neste instante; em teste, a pool fica ociosa sem isto.
   */
  private long waitingBackends() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from pg_stat_activity where wait_event_type = 'Lock'")) {
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }
  }

  /**
   * Comita a fixture (operador + produto + saldo de 7.000) fora de {@code @TestTransaction}: as
   * duas transações do teste precisam enxergar a linha, e o segundo movimento do produto não pode
   * passar pelo insert sob demanda.
   */
  private UUID seedCommittedStockRow() {
    UUID seededStoreId = storeId();
    userId =
        callInOwnTransaction(
            () -> userStore.insert(new NewUser(OPERATOR, "Operador de estoque", "hash", "ACTIVE")));
    productId =
        callInOwnTransaction(
            () ->
                productStore.insert(
                    new NewProduct(
                        seededStoreId,
                        PRODUCT_NAME,
                        null,
                        null,
                        null,
                        "UN",
                        new BigDecimal("9.90"),
                        null)));
    stockId =
        callInOwnTransaction(
            () -> {
              stockRepository.insertIfAbsent(seededStoreId, productId);
              return stockRepository.findByProduct(seededStoreId, productId).orElseThrow().id();
            });
    callInOwnTransaction(
        () -> {
          stockRepository.updateQuantity(stockId, new BigDecimal(SEEDED_QUANTITY));
          return null;
        });
    return productId;
  }

  /** Roda a tarefa em transação própria, com o request context do Arc ativado na thread. */
  private static void inOwnTransaction(Runnable work) {
    ManagedContext requestContext = Arc.container().requestContext();
    boolean activated = !requestContext.isActive();
    if (activated) {
      requestContext.activate();
    }
    try {
      QuarkusTransaction.requiringNew().run(work);
    } finally {
      if (activated) {
        requestContext.terminate();
      }
    }
  }

  /** Roda a tarefa em transação própria e devolve o resultado. */
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

  private static void awaitLatch(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrompido esperando a primeira soltar o lock", interrupted);
    }
  }

  /** Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}. */
  @AfterEach
  void removeCommittedFixture() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      execute(connection, "delete from stock_movements where product_id = ?", productId);
      execute(connection, "delete from product_stocks where product_id = ?", productId);
      execute(connection, "delete from products where id = ?", productId);
      execute(connection, "delete from users where id = ?", userId);
    }
  }

  private static void execute(Connection connection, String sql, UUID id) throws SQLException {
    if (id == null) {
      return;
    }
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      statement.executeUpdate();
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
}
