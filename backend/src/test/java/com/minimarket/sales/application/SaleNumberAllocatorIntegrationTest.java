package com.minimarket.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.IntegrationTestBase;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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
import java.util.stream.LongStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link SaleNumberAllocator} contra PostgreSQL real (Dev Services): a série da venda
 * por loja sobre {@code document_sequences} — critério de aceite do passo 804.
 *
 * <p>A corrida segue o padrão do {@code StockConcurrencyTest}: cada worker roda em thread própria,
 * com o request context do Arc ativado e transação própria ({@code
 * QuarkusTransaction.requiringNew}), e o {@link CountDownLatch} solta todos no mesmo instante — sem
 * {@code sleep}. O que o teste prova é a invariante do banco — 50 números distintos, exatamente
 * {1..50} —, nunca a ordem em que as threads chegaram: ordem é do escalonador, efeito é do lock.
 *
 * <p>Sem {@code @TestTransaction}: os workers comitam de verdade, a thread principal não segura
 * conexão enquanto eles rodam e a limpeza por SQL devolve a linha de {@code (MATRIZ, 'SALE')} ao
 * estado inicial — antes e depois de cada teste, porque a linha nasce na primeira alocação e o
 * banco é compartilhado com os demais testes do fork.
 */
@QuarkusTest
class SaleNumberAllocatorIntegrationTest extends IntegrationTestBase {

  /** Workers da corrida: um número por worker, todos no mesmo instante. */
  private static final int ALLOCATION_WORKERS = 50;

  /** Série da venda em {@code document_sequences}; a NFC-e da Fase 14 terá a própria. */
  private static final String SALE_DOC_TYPE = "SALE";

  /** Espera máxima de cada worker: o teste falha, nunca trava, se a corrida não terminar. */
  private static final long WORKER_TIMEOUT_SECONDS = 30;

  @Inject SaleNumberAllocator saleNumberAllocator;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  private UUID storeId;

  @Test
  @DisplayName("50 alocações simultâneas devolvem os números de 1 a 50, sem repetir nem pular")
  void allocatesFiftyNumbersConcurrently() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(ALLOCATION_WORKERS);
    List<Long> numbers = new ArrayList<>();
    try {
      List<Future<Long>> futures =
          IntStream.range(0, ALLOCATION_WORKERS)
              .mapToObj(
                  index ->
                      workers.submit(
                          () -> {
                            start.await();
                            return inOwnTransaction(
                                () -> saleNumberAllocator.nextNumber(storeId()));
                          }))
              .toList();
      start.countDown();
      for (Future<Long> future : futures) {
        numbers.add(await(future));
      }
    } finally {
      workers.shutdown();
      assertThat(workers.awaitTermination(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("workers terminaram")
          .isTrue();
    }

    assertThat(numbers)
        .as("nenhum número repetido: o lock da linha serializa as alocações concorrentes")
        .doesNotHaveDuplicates();
    assertThat(numbers)
        .as("nenhum buraco: o conjunto alocado é exatamente {1..50}")
        .containsExactlyInAnyOrderElementsOf(
            LongStream.rangeClosed(1, ALLOCATION_WORKERS).boxed().toList());
    assertThat(nextValue())
        .as("a série avança um por alocação: next_value = 51 depois de 50")
        .isEqualTo(ALLOCATION_WORKERS + 1L);
  }

  @Test
  @DisplayName("alocações sequenciais devolvem 1, 2 e 3 e deixam next_value em 4")
  void allocatesSequentially() throws SQLException {
    assertThat(inOwnTransaction(() -> saleNumberAllocator.nextNumber(storeId())))
        .as("a primeira alocação da loja cria a linha da série e devolve 1")
        .isEqualTo(1L);
    assertThat(inOwnTransaction(() -> saleNumberAllocator.nextNumber(storeId()))).isEqualTo(2L);
    assertThat(inOwnTransaction(() -> saleNumberAllocator.nextNumber(storeId()))).isEqualTo(3L);
    assertThat(nextValue()).isEqualTo(4L);
  }

  @Test
  @DisplayName("alocação desfeita por rollback não consome número: a próxima devolve o mesmo")
  void rolledBackAllocationDoesNotConsumeNumber() throws SQLException {
    assertThat(inOwnTransaction(() -> saleNumberAllocator.nextNumber(storeId()))).isEqualTo(1L);

    assertThatThrownBy(() -> inOwnTransaction(() -> allocateAndFail()))
        .as("o erro do caso de uso desfaz a transação inteira, alocação incluída")
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("rollback proposital");

    assertThat(inOwnTransaction(() -> saleNumberAllocator.nextNumber(storeId())))
        .as("o número da transação desfeita volta a ser alocado: a série não abre buraco")
        .isEqualTo(2L);
    assertThat(nextValue()).isEqualTo(3L);
  }

  /** Aloca como o caso de uso faria e derruba a transação em seguida, como um erro real. */
  private long allocateAndFail() throws SQLException {
    saleNumberAllocator.nextNumber(storeId());
    throw new IllegalStateException("rollback proposital");
  }

  /** {@code next_value} da série da venda, como o banco o guardou. */
  private long nextValue() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select next_value from document_sequences where store_id = ? and doc_type = ?")) {
      statement.setObject(1, storeId());
      statement.setString(2, SALE_DOC_TYPE);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next())
            .as("linha da série da venda da loja %s presente", storeId())
            .isTrue();
        return resultSet.getLong(1);
      }
    }
  }

  /**
   * Devolve a série da venda ao estado inicial (sem linha) antes e depois de cada teste: a linha
   * nasce na primeira alocação, então nenhum teste pode herdar o contador do anterior.
   */
  @BeforeEach
  void removeStaleSequence() throws SQLException {
    deleteSaleSequence();
  }

  @AfterEach
  void removeCommittedSequence() throws SQLException {
    deleteSaleSequence();
  }

  private void deleteSaleSequence() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "delete from document_sequences where store_id = ? and doc_type = ?")) {
      statement.setObject(1, storeId());
      statement.setString(2, SALE_DOC_TYPE);
      statement.executeUpdate();
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

  /** Id da loja do seed (MATRIZ), lido direto do banco: a série é por loja. */
  private UUID storeId() throws SQLException {
    if (storeId == null) {
      try (Connection connection = dataSource.getConnection();
          PreparedStatement statement =
              connection.prepareStatement("select id from stores where code = ?")) {
        statement.setString(1, defaultStoreCode);
        try (ResultSet resultSet = statement.executeQuery()) {
          assertThat(resultSet.next())
              .as("loja %s do seed da V1 presente", defaultStoreCode)
              .isTrue();
          storeId = resultSet.getObject(1, UUID.class);
        }
      }
    }
    return storeId;
  }
}
