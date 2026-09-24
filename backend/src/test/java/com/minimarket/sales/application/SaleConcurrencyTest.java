package com.minimarket.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.cash.application.OpenCashSessionCommand;
import com.minimarket.cash.application.OpenCashSessionUseCase;
import com.minimarket.catalog.application.NewProduct;
import com.minimarket.catalog.application.ProductStore;
import com.minimarket.sales.domain.Sale;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
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
 * Concorrência do {@link AddSaleItemUseCase} contra PostgreSQL real (Dev Services): o lock otimista
 * da venda sob disputa de verdade, fechando a Fase 8 (passo 814a).
 *
 * <p>Dois cenários. O determinístico prova o 409: uma thread abre transação própria, lê a venda por
 * {@link SaleStore#findById} — a entidade fica com a versão vencida no contexto de persistência — e
 * segura; o vencedor roda a inclusão completa e comita; então a thread vencida chama a inclusão
 * <em>na mesma transação</em> (o {@code @Transactional} junta) e o flush do {@code update} do 803
 * acusa a versão vencida. O de corrida real prova o invariante: {@link #ROUNDS} rodadas de duas
 * inclusões soltas no mesmo latch, na mesma venda e no mesmo produto — nunca as duas falham, toda
 * falha é {@code CONCURRENT_MODIFICATION} e o banco termina com uma linha por produto, a quantidade
 * somando uma por sucesso e um {@code SALE_ITEM_ADDED} por sucesso.
 *
 * <p>Sem {@code sleep} e sem {@code @Disabled}: quem solta as threads é o {@link CountDownLatch} e
 * quem garante o resultado é o lock — o teste confere efeitos (quantidade, totais, eventos), nunca
 * a ordem em que as threads chegaram. Cada worker roda com o request context do Arc ativado e
 * transação própria ({@code QuarkusTransaction.requiringNew}), como no {@code
 * StockConcurrencyTest}, e a thread que falha vira a falha do teste com a causa.
 *
 * <p>Sem {@code @TestTransaction}: os casos de uso comitam de verdade e o {@code @AfterEach} limpa
 * o que comitou na ordem das FKs — itens → venda → eventos → série → movimentos → sessão de caixa →
 * usuário → produtos —, porque o banco é compartilhado com os demais testes do fork.
 */
@QuarkusTest
class SaleConcurrencyTest extends IntegrationTestBase {

  /** Rodadas do cenário de corrida: cada uma repete a colisão de duas inclusões. */
  private static final int ROUNDS = 20;

  /** Quantidade de cada inclusão: o item final tem uma por sucesso. */
  private static final BigDecimal QUANTITY = BigDecimal.ONE;

  /** Espera máxima de cada worker: o teste falha, nunca trava, se a corrida não terminar. */
  private static final long WORKER_TIMEOUT_SECONDS = 30;

  /** Preço do produto dos cenários: os totais conferidos saem dele. */
  private static final BigDecimal PRICE = new BigDecimal("9.90");

  private static final String OPERATOR = "vendas.concorrencia";

  /**
   * Série da venda em {@code document_sequences}; a limpeza devolve o contador ao estado inicial.
   */
  private static final String SALE_DOC_TYPE = "SALE";

  @Inject SaleStore saleStore;

  @Inject AddSaleItemUseCase addSaleItemUseCase;

  @Inject CreateSaleUseCase createSaleUseCase;

  @Inject OpenCashSessionUseCase openCashSessionUseCase;

  @Inject ProductStore productStore;

  @Inject UserStore userStore;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  private UUID storeId;

  private UUID userId;

  private UUID registerId;

  private UUID cashSessionId;

  /** Fixtures comitadas pelos testes, para a limpeza na ordem das FKs. */
  private final List<UUID> saleIds = new ArrayList<>();

  private final List<UUID> productIds = new ArrayList<>();

  @Test
  @DisplayName(
      "inclusão na venda lida antes da gravação concorrente vira 409 CONCURRENT_MODIFICATION sem escrita parcial")
  void staleAddItemConflictsWithoutPartialWrite() throws Exception {
    Sale sale = openScenario();
    UUID winnerProduct = newProduct("Produto do vencedor");
    UUID loserProduct = newProduct("Produto do perdedor");

    CountDownLatch staleRead = new CountDownLatch(1);
    CountDownLatch winnerCommitted = new CountDownLatch(1);
    ExecutorService loserThread = Executors.newSingleThreadExecutor();
    try {
      Future<ErrorCode> loser =
          loserThread.submit(
              () -> {
                try {
                  inOwnTransaction(
                      () -> {
                        // A leitura deixa a entidade com a versão 0 no contexto de persistência —
                        // é ela que a inclusão vencida vai tentar gravar depois do commit alheio.
                        saleStore.findById(sale.id()).orElseThrow();
                        staleRead.countDown();
                        await(winnerCommitted);
                        addSaleItemUseCase.execute(
                            new AddSaleItemCommand(
                                sale.id(), registerId, null, loserProduct, QUANTITY));
                        return null;
                      });
                  throw new AssertionError("a inclusão vencida não pode ter sucesso");
                } catch (ConflictException conflict) {
                  return conflict.code();
                }
              });

      assertThat(staleRead.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("a leitura vencida aconteceu antes da gravação do vencedor")
          .isTrue();

      Sale updated =
          addSaleItemUseCase.execute(
              new AddSaleItemCommand(
                  sale.id(), registerId, null, winnerProduct, new BigDecimal("2")));

      assertThat(updated.items()).as("o vencedor gravou o item dele").hasSize(1);
      winnerCommitted.countDown();

      assertThat(await(loser))
          .as("o lock otimista é o 409 do perdedor")
          .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
    } finally {
      winnerCommitted.countDown();
      loserThread.shutdown();
      assertThat(loserThread.awaitTermination(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("worker terminou")
          .isTrue();
    }

    // O vencedor ficou inteiro e o perdedor não deixou nada: sem escrita parcial nem evento órfão.
    List<ItemRow> items = itemRows(sale.id());

    assertThat(items).as("uma linha só: a do vencedor").hasSize(1);
    assertThat(items.getFirst().productId()).isEqualTo(winnerProduct);
    assertThat(items.getFirst().quantity()).isEqualByComparingTo("2.000");
    assertThat(items.getFirst().lineTotal()).isEqualByComparingTo("19.80");
    assertThat(items)
        .extracting(ItemRow::productId)
        .as("o item do perdedor não existe")
        .doesNotContain(loserProduct);

    SaleRow stored = saleRow(sale.id());

    assertThat(stored.itemCount()).isEqualTo(1);
    assertThat(stored.subtotal()).isEqualByComparingTo("19.80");
    assertThat(stored.total()).isEqualByComparingTo("19.80");
    assertThat(stored.version()).as("só o vencedor gravou a venda").isEqualTo(1L);
    assertThat(eventProductIds(sale.id()))
        .as("um SALE_ITEM_ADDED, do vencedor; o perdedor não inventou evento")
        .containsExactly(winnerProduct);
  }

  @Test
  @DisplayName(
      "20 rodadas de duas inclusões simultâneas: nunca as duas falham, nenhum update se perde e um evento por sucesso")
  void concurrentAddsNeverLoseAnUpdate() throws Exception {
    Sale sale = openScenario();
    UUID productId = newProduct("Produto da corrida");

    int successes = 0;
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      for (int round = 1; round <= ROUNDS; round++) {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> raced =
            IntStream.range(0, 2)
                .mapToObj(
                    index ->
                        workers.submit(
                            () -> {
                              start.await();
                              return addItemInOwnTransaction(sale.id(), productId);
                            }))
                .toList();
        start.countDown();

        List<Boolean> results = new ArrayList<>();
        for (Future<Boolean> result : raced) {
          results.add(await(result));
        }
        assertThat(results).as("rodada %d: pelo menos uma inclusão venceu", round).contains(true);
        successes += (int) results.stream().filter(Boolean::booleanValue).count();
      }
    } finally {
      workers.shutdown();
      assertThat(workers.awaitTermination(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("workers terminaram")
          .isTrue();
    }

    // Invariante final sem update perdido: um update por sucesso, uma linha somando uma por sucesso
    // e um evento por sucesso — a transação vencida não deixa rastro.
    BigDecimal quantity = QUANTITY.multiply(BigDecimal.valueOf(successes));
    BigDecimal expectedTotal = PRICE.multiply(quantity);
    SaleRow stored = saleRow(sale.id());

    assertThat(stored.version())
        .as("um update da venda por inclusão vencedora")
        .isEqualTo(successes);
    assertThat(stored.itemCount())
        .as("a linha do produto nunca é duplicada nem perdida")
        .isEqualTo(1);
    assertThat(stored.subtotal()).isEqualByComparingTo(expectedTotal);
    assertThat(stored.total()).isEqualByComparingTo(expectedTotal);

    List<ItemRow> items = itemRows(sale.id());

    assertThat(items).hasSize(1);
    assertThat(items.getFirst().productId()).isEqualTo(productId);
    assertThat(items.getFirst().quantity())
        .as("%d inclusões de %s somam %s", successes, QUANTITY.toPlainString(), quantity)
        .isEqualByComparingTo(quantity);
    assertThat(items.getFirst().lineTotal()).isEqualByComparingTo(expectedTotal);
    assertThat(eventCount(sale.id(), "SALE_ITEM_ADDED"))
        .as("um SALE_ITEM_ADDED por sucesso")
        .isEqualTo(successes);
  }

  /**
   * Inclusão em transação própria: {@code true} quando venceu; o 409 do lock vira {@code false}.
   */
  private boolean addItemInOwnTransaction(UUID saleId, UUID productId) {
    try {
      inOwnTransaction(
          () ->
              addSaleItemUseCase.execute(
                  new AddSaleItemCommand(saleId, registerId, null, productId, QUANTITY)));
      return true;
    } catch (ConflictException conflict) {
      assertThat(conflict.code())
          .as("a única falha possível na corrida é o lock otimista")
          .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
      return false;
    }
  }

  /** Operador, CAIXA-01 com sessão aberta e venda aberta: as fixtures dos dois cenários. */
  private Sale openScenario() throws SQLException {
    userId =
        inOwnTransaction(
            () -> userStore.insert(new NewUser(OPERATOR, "Operador de venda", "hash", "ACTIVE")));
    registerId = cashRegisterId("CAIXA-01");
    cashSessionId =
        openCashSessionUseCase
            .execute(new OpenCashSessionCommand(registerId, new BigDecimal("100.00"), userId, null))
            .id();
    Sale sale = createSaleUseCase.execute(new CreateSaleCommand(registerId, userId));

    saleIds.add(sale.id());
    return sale;
  }

  /** Produto vivo da loja, pela porta do catálogo. */
  private UUID newProduct(String name) {
    UUID id =
        inOwnTransaction(
            () ->
                productStore.insert(
                    new NewProduct(storeId(), name, null, null, null, "UN", PRICE, null)));
    productIds.add(id);
    return id;
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

  /** Espera o sinal com teto de tempo: o teste falha, nunca trava. */
  private static void await(CountDownLatch signal) {
    try {
      if (!signal.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new AssertionError("sinal não chegou em %ds".formatted(WORKER_TIMEOUT_SECONDS));
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("teste interrompido esperando o sinal", interrupted);
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

  /** Itens da venda na ordem de {@code line_number}, como o banco os guardou. */
  private List<ItemRow> itemRows(UUID saleId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select product_id, quantity, line_total from sale_items"
                    + " where sale_id = ? order by line_number")) {
      statement.setObject(1, saleId);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<ItemRow> rows = new ArrayList<>();
        while (resultSet.next()) {
          rows.add(
              new ItemRow(
                  resultSet.getObject("product_id", UUID.class),
                  resultSet.getBigDecimal("quantity"),
                  resultSet.getBigDecimal("line_total")));
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
                "select subtotal, total, item_count, version from sales where id = ?")) {
      statement.setObject(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("venda %s gravada", id).isTrue();
        return new SaleRow(
            resultSet.getBigDecimal("subtotal"),
            resultSet.getBigDecimal("total"),
            resultSet.getInt("item_count"),
            resultSet.getLong("version"));
      }
    }
  }

  /** Eventos da ação para a venda: um por operação efetivada, zero por transação vencida. */
  private int eventCount(UUID saleId, String action) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from audit_events where entity_id = ? and action = ?")) {
      statement.setObject(1, saleId);
      statement.setString(2, action);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  /** Produto de cada {@code SALE_ITEM_ADDED} da venda, na ordem de gravação. */
  private List<UUID> eventProductIds(UUID saleId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select (details->>'productId')::uuid as product_id from audit_events"
                    + " where entity_id = ? and action = 'SALE_ITEM_ADDED' order by id")) {
      statement.setObject(1, saleId);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<UUID> productIds = new ArrayList<>();
        while (resultSet.next()) {
          productIds.add(resultSet.getObject("product_id", UUID.class));
        }
        return productIds;
      }
    }
  }

  /** Remove o que os testes comitaram, na ordem que as FKs exigem — o banco é compartilhado. */
  @AfterEach
  void removeCommittedFixture() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      for (UUID saleId : saleIds) {
        execute(connection, "delete from sale_items where sale_id = ?", saleId);
        execute(connection, "delete from sales where id = ?", saleId);
        execute(connection, "delete from audit_events where entity_id = ?", saleId);
      }
      execute(connection, "delete from audit_events where entity_id = ?", cashSessionId);
      execute(
          connection,
          "delete from document_sequences where store_id = ? and doc_type = ?",
          storeId(),
          SALE_DOC_TYPE);
      execute(connection, "delete from cash_movements where cash_session_id = ?", cashSessionId);
      execute(connection, "delete from cash_sessions where id = ?", cashSessionId);
      execute(connection, "delete from users where id = ?", userId);
      for (UUID productId : productIds) {
        execute(connection, "delete from products where id = ?", productId);
      }
    }
  }

  private static void execute(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
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

  /** Linha de {@code sale_items} reduzida ao que as invariantes conferem. */
  private record ItemRow(UUID productId, BigDecimal quantity, BigDecimal lineTotal) {}

  /** Cabeçalho de {@code sales} como o banco o guardou. */
  private record SaleRow(BigDecimal subtotal, BigDecimal total, int itemCount, long version) {}
}
