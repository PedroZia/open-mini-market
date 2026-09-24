package com.minimarket.audit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import io.quarkus.test.junit.QuarkusTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plano de execução da consulta de auditoria com volume (passo 1003): com 100 mil eventos e
 * estatística fresca, os dois acessos que o {@link AuditEventQueryRepository} monta — por alvo
 * ({@code entity_type + entity_id}, o histórico de uma venda) e por período ({@code from}/{@code
 * to}) — precisam passar pelos índices da V6, sem {@code Seq Scan} (§5.3). O PostgreSQL é o real do
 * Dev Services (Docker precisa estar rodando): plano de {@code EXPLAIN} só diz algo sobre o banco
 * que vai rodar isto.
 *
 * <p>O seed é o do passo: 100 mil eventos gravados por JDBC em lote, numa transação, espalhados por
 * ~12 meses, com a venda dominando o log (60%) sobre um pool de 2.000 vendas — ~30 eventos por
 * venda, que é a seletividade que a consulta por entidade enxerga na vida real. O {@code ANALYZE}
 * não é opcional: sem estatística fresca o planner decide com a tabela vazia e o teste mediria
 * outra coisa.
 *
 * <p>Os dois {@code EXPLAIN (ANALYZE, BUFFERS)} espelham a SQL que o Hibernate gera para o
 * adaptador — capturada no log do {@code org.hibernate.SQL}: {@code select ... from audit_events
 * aee1_0 where 1=1 and aee1_0.entity_type=? and aee1_0.entity_id=? order by aee1_0.occurred_at
 * desc,aee1_0.id desc offset ? rows fetch first ? rows only}. O teste troca os parâmetros por
 * literais e o {@code offset ? rows fetch first ? rows only} pelo {@code limit 20} equivalente —
 * para o planner é o mesmo nó {@code Limit} — e cada um tem de mostrar um nó de índice sobre o
 * {@code ix_} esperado. O plano sai no console: é o número que o passo manda registrar no commit, e
 * a asserção checa o essencial (nenhum {@code Seq Scan} e o índice certo em uso).
 *
 * <p>O seed é limpo a cada teste, com a estatística refeita, para as outras suítes verem a tabela
 * como a encontraram.
 */
@QuarkusTest
class AuditEventQueryPlanTest extends IntegrationTestBase {

  /** Marcador exclusivo do seed: a limpeza é um {@code delete} por ele, nunca uma varredura. */
  private static final String MARKER = "perf-audit-1003";

  /** Volume do passo: o tamanho em que o planner começa a decidir entre índice e tabela inteira. */
  private static final int EVENT_COUNT = 100_000;

  private static final int BATCH_SIZE = 1_000;

  /** Fatias do log: 12 de 20 para venda (60%), o resto entre produto, caixa, usuário e cliente. */
  private static final int SLOTS = 20;

  /** Base fixa do seed: o teste não depende do relógio da máquina. */
  private static final Instant PERIOD_START = Instant.parse("2025-09-01T00:00:00Z");

  /** ~5 min entre eventos: 100 mil eventos cobrem os ~12 meses de log. */
  private static final long SECONDS_PER_EVENT = 315;

  /** Janela do caso "por período": um dia no meio do seed (~270 eventos). */
  private static final Instant WINDOW_START = PERIOD_START.plusSeconds(180L * 86_400);

  private static final Instant WINDOW_END = WINDOW_START.plusSeconds(86_400);

  /**
   * Origens válidas do check da V6: o log do PDV nasce na API e na TUI, com pouco de WEB/SYSTEM.
   */
  private static final List<String> SOURCES =
      List.of("API", "API", "TUI", "TUI", "API", "TUI", "TUI", "SYSTEM");

  private static final Profile SALE_PROFILE =
      new Profile(
          "SALE",
          pool(1, 2_000),
          List.of("SALE_CREATED", "SALE_ITEM_ADDED", "PAYMENT_ADDED", "SALE_COMPLETED"));

  private static final Profile PRODUCT_PROFILE =
      new Profile(
          "PRODUCT",
          pool(2, 1_500),
          List.of("PRODUCT_CREATED", "PRODUCT_UPDATED", "PRODUCT_PRICE_CHANGED"));

  private static final Profile CASH_SESSION_PROFILE =
      new Profile(
          "CASH_SESSION",
          pool(3, 1_000),
          List.of("CASH_SESSION_OPENED", "CASH_WITHDRAWAL", "CASH_SUPPLY", "CASH_SESSION_CLOSED"));

  private static final Profile USER_PROFILE =
      new Profile("USER", pool(4, 50), List.of("USER_CREATED", "USER_UPDATED", "USER_DISABLED"));

  private static final Profile CUSTOMER_PROFILE =
      new Profile(
          "CUSTOMER",
          pool(5, 1_000),
          List.of("CUSTOMER_CREATED", "CUSTOMER_UPDATED", "CUSTOMER_DISABLED"));

  /** Atores do seed: uuid de usuário não tem FK no log e é só mais um campo do evento. */
  private static final List<UUID> ACTOR_IDS = pool(6, 50);

  /** A venda do caso "por entidade": tem ~36 eventos no seed, como uma venda no log real. */
  private static final UUID SALE_ID = SALE_PROFILE.entityIds().getFirst();

  /**
   * Insert do seed, só com as colunas que dão forma ao log: o alvo, a ação, o ator e a origem. O
   * {@code details} fica com o default da coluna ({@code '{}'}) e o que a consulta não toca (loja,
   * sessões, caixa, IP) fica nulo — o plano só olha {@code occurred_at}, {@code entity_type},
   * {@code entity_id} e {@code id}.
   */
  private static final String INSERT =
      "insert into audit_events"
          + " (occurred_at, actor_user_id, actor_username, action, entity_type, entity_id, source,"
          + " request_id) values (?::timestamptz, ?::uuid, ?, ?, ?, ?::uuid, ?, ?)";

  @BeforeEach
  void seedEvents() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement clear =
            connection.prepareStatement("delete from audit_events where request_id = ?");
        PreparedStatement insert = connection.prepareStatement(INSERT)) {
      connection.setAutoCommit(false);
      clear.setString(1, MARKER);
      clear.executeUpdate();
      for (int index = 0; index < EVENT_COUNT; index++) {
        bind(insert, index);
        insert.addBatch();
        if ((index + 1) % BATCH_SIZE == 0) {
          insert.executeBatch();
        }
      }
      insert.executeBatch();
      connection.commit();
    }
    analyze();
  }

  @AfterEach
  void removeSeed() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement clear =
            connection.prepareStatement("delete from audit_events where request_id = ?")) {
      clear.setString(1, MARKER);
      clear.executeUpdate();
    }
    analyze();
  }

  @Test
  @DisplayName(
      "100 mil eventos: a consulta por entidade usa o índice (entity_type, entity_id, occurred_at) e não varre a tabela")
  void usesEntityIndex() throws SQLException {
    String plan =
        explain(
            "select * from audit_events where entity_type = '"
                + SALE_PROFILE.entityType()
                + "' and entity_id = '"
                + SALE_ID
                + "'::uuid order by occurred_at desc, id desc limit 20");

    report("por entidade (entity_type + entity_id)", plan);

    assertThat(plan).as("plano por entidade sem Seq Scan%n%s", plan).doesNotContain("Seq Scan");
    assertThat(plan)
        .as("plano por entidade com nó de índice sobre o alvo%n%s", plan)
        .contains("ix_audit_events_entity_occurred_at");
  }

  @Test
  @DisplayName(
      "100 mil eventos: a consulta por período usa o índice (occurred_at desc) e não varre a tabela")
  void usesPeriodIndex() throws SQLException {
    String plan =
        explain(
            "select * from audit_events where occurred_at >= '"
                + WINDOW_START
                + "' and occurred_at < '"
                + WINDOW_END
                + "' order by occurred_at desc, id desc limit 20");

    report("por período (from + to)", plan);

    assertThat(plan).as("plano por período sem Seq Scan%n%s", plan).doesNotContain("Seq Scan");
    assertThat(plan)
        .as("plano por período com nó de índice sobre o intervalo%n%s", plan)
        .contains("ix_audit_events_occurred_at");
  }

  /**
   * Preenche o insert do {@code index}-ésimo evento: o slot decide o tipo de entidade e as ações
   * plausíveis, e o mesmo {@code index / 20} escolhe o alvo dentro do pool — assim cada venda (e
   * cada produto, sessão de caixa, usuário, cliente) acumula dezenas de eventos no seed.
   */
  private static void bind(PreparedStatement statement, int index) throws SQLException {
    Profile profile = profileOf(index);

    statement.setString(1, PERIOD_START.plusSeconds(index * SECONDS_PER_EVENT).toString());
    statement.setObject(2, ACTOR_IDS.get(index % ACTOR_IDS.size()));
    statement.setString(3, "operador." + (index % ACTOR_IDS.size()));
    statement.setString(4, profile.actions().get(index % profile.actions().size()));
    statement.setString(5, profile.entityType());
    statement.setObject(6, profile.entityIds().get((index / SLOTS) % profile.entityIds().size()));
    statement.setString(7, SOURCES.get(index % SOURCES.size()));
    statement.setString(8, MARKER);
  }

  /**
   * Tipo de entidade do i-ésimo evento, pela fatia do slot: 12/20 venda, 3 produto, 2+2+1 o resto.
   */
  private static Profile profileOf(int index) {
    int slot = index % SLOTS;

    if (slot < 12) {
      return SALE_PROFILE;
    }
    if (slot < 15) {
      return PRODUCT_PROFILE;
    }
    if (slot < 17) {
      return CASH_SESSION_PROFILE;
    }
    if (slot < 18) {
      return USER_PROFILE;
    }
    return CUSTOMER_PROFILE;
  }

  /**
   * Pool determinístico de ids sintéticos do seed — o log não tem FK, então não precisa de entidade
   * de verdade atrás do id; o {@code tag} só mantém pools diferentes sem id repetido, para o plano
   * impresso ser sempre o mesmo.
   */
  private static List<UUID> pool(int tag, int size) {
    return IntStream.range(0, size)
        .mapToObj(
            index -> UUID.fromString("00000000-0000-7000-8000-%02d%010d".formatted(tag, index)))
        .toList();
  }

  /** {@code EXPLAIN (ANALYZE, BUFFERS)} de verdade: a consulta roda e o plano volta como texto. */
  private String explain(String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery("explain (analyze, buffers) " + sql)) {
      StringBuilder plan = new StringBuilder();
      while (resultSet.next()) {
        plan.append(resultSet.getString(1)).append('\n');
      }
      return plan.toString();
    }
  }

  /** {@code ANALYZE} explícito: sem estatística fresca o planner decide no escuro. */
  private void analyze() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("analyze audit_events");
    }
  }

  /** O plano no console: é o número da medição (nó, tempo, buffers) que o passo registra. */
  private static void report(String shape, String plan) {
    System.out.println("--- EXPLAIN (ANALYZE, BUFFERS) " + shape + " ---");
    System.out.println(plan);
  }

  /** Tipo de entidade do log, o pool de alvos e as ações plausíveis daquele tipo (§7.2). */
  private record Profile(String entityType, List<UUID> entityIds, List<String> actions) {}
}
