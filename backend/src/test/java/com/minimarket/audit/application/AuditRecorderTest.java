package com.minimarket.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.IntegrationTestBase;
import com.minimarket.shared.application.OperationContext;
import com.minimarket.shared.domain.OperationSource;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gravador de auditoria contra PostgreSQL real (Dev Services): o evento comita com o contexto da
 * requisição, a transação desfeita não deixa linha órfã e a falha ao gravar propaga.
 *
 * <p>O request context é ativado na thread do teste (fora de um request HTTP ninguém o ativa) e o
 * {@link OperationContext} é preenchido à mão — o preenchimento pelo filtro já tem cobertura
 * própria (passo 302). A leitura é por SQL, não pela entidade: o banco é a fonte de verdade do que
 * foi gravado, inclusive do jsonb e do inet, e a entidade JPA não é lida neste passo.
 */
@QuarkusTest
class AuditRecorderTest extends IntegrationTestBase {

  private static final String ACTOR = "audit.recorder.teste";
  private static final InetAddress IP = InetAddress.ofLiteral("203.0.113.7");

  @Inject AuditRecorder recorder;

  /** Contexto preenchido pelo filtro em produção; aqui o teste o preenche à mão. */
  @Inject OperationContext operationContext;

  /**
   * Alvos dos eventos deste teste, para a limpeza (o log é append-only e o teste comita de
   * verdade).
   */
  private final List<UUID> recordedEntities = new ArrayList<>();

  @Test
  @DisplayName("grava o evento com todos os campos do contexto da requisição")
  void recordsEventWithRequestContext() throws SQLException {
    UUID entityId = newEntityId();
    Actor actor = new Actor();
    Instant before = Instant.now().minusSeconds(5);

    inRequestContext(
        () -> {
          fillContext(actor);
          QuarkusTransaction.requiringNew()
              .run(
                  () ->
                      recorder.record(
                          "SALE_CREATED",
                          "SALE",
                          entityId,
                          "venda aberta no caixa",
                          Map.of("total", "10.00")));
        });

    Event event = eventOf(entityId);
    assertThat(event.action()).isEqualTo("SALE_CREATED");
    assertThat(event.entityType()).isEqualTo("SALE");
    assertThat(event.storeId()).isEqualTo(actor.storeId().toString());
    assertThat(event.actorUserId()).isEqualTo(actor.userId().toString());
    assertThat(event.actorUsername()).isEqualTo(ACTOR);
    assertThat(event.authSessionId()).isEqualTo(actor.sessionId().toString());
    assertThat(event.cashRegisterId()).isEqualTo(actor.cashRegisterId().toString());
    assertThat(event.cashSessionId()).as("sessão de caixa só nasce na Fase 6").isNull();
    assertThat(event.source()).as("origem vem do cliente da sessão").isEqualTo("TUI");
    assertThat(event.requestId()).isEqualTo(actor.requestId());
    assertThat(event.reason()).isEqualTo("venda aberta no caixa");
    assertThat(event.details())
        .as("details jsonb como o PostgreSQL o devolve")
        .isEqualTo("{\"total\": \"10.00\"}");
    assertThat(event.ip()).isEqualTo(IP.getHostAddress());
    assertThat(event.occurredAt().toInstant())
        .as("occurred_at preenchido pelo default now() do banco")
        .isBetween(before, Instant.now().plusSeconds(5));
  }

  @Test
  @DisplayName("details ausente vira jsonb vazio, o default da coluna")
  void recordsEmptyDetailsWhenNull() throws SQLException {
    UUID entityId = newEntityId();

    inRequestContext(
        () -> {
          fillContext(new Actor());
          QuarkusTransaction.requiringNew()
              .run(() -> recorder.record("LOGIN_FAILED", "USER", entityId, "senha inválida", null));
        });

    assertThat(eventOf(entityId).details()).isEqualTo("{}");
  }

  @Test
  @DisplayName("transação desfeita não deixa evento órfão")
  void leavesNoEventWhenTransactionRollsBack() throws SQLException {
    UUID entityId = newEntityId();

    inRequestContext(
        () -> {
          fillContext(new Actor());
          assertThatThrownBy(
                  () ->
                      QuarkusTransaction.requiringNew()
                          .run(
                              () -> {
                                recorder.record(
                                    "SALE_CANCELLED", "SALE", entityId, "não deve sobrar", null);
                                throw new IllegalStateException("falha depois da auditoria");
                              }))
              .isInstanceOf(IllegalStateException.class);
        });

    assertThat(countByEntity(entityId)).as("o evento desfez junto com a operação").isZero();
  }

  @Test
  @DisplayName("falha ao gravar propaga em vez de ser engolida")
  void propagatesInsertFailure() throws SQLException {
    UUID entityId = newEntityId();

    inRequestContext(
        () -> {
          fillContext(new Actor());
          assertThatThrownBy(
                  () ->
                      QuarkusTransaction.requiringNew()
                          .run(() -> recorder.record(null, "SALE", entityId, null, null)))
              .as("action é obrigatória na coluna e a exceção não pode ser silenciada")
              .isInstanceOf(PersistenceException.class);
        });

    assertThat(countByEntity(entityId)).isZero();
  }

  @Test
  @DisplayName("fora de request HTTP o evento nasce com ator nulo e origem SYSTEM")
  void recordsSystemEventWithoutRequestContext() throws Exception {
    UUID entityId = newEntityId();

    // Thread nova: contexto CDI é thread-bound, então aqui não existe request context — o caso da
    // tarefa de sistema, que roda sem requisição HTTP.
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      executor
          .submit(
              () ->
                  QuarkusTransaction.requiringNew()
                      .run(
                          () ->
                              recorder.record(
                                  "LOGIN_LOCKED",
                                  "USER",
                                  entityId,
                                  "bloqueio por tentativas",
                                  null)))
          .get(30, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    Event event = eventOf(entityId);
    assertThat(event.source()).as("sem requisição a origem é SYSTEM").isEqualTo("SYSTEM");
    assertThat(event.actorUserId()).isNull();
    assertThat(event.actorUsername()).isNull();
    assertThat(event.authSessionId()).isNull();
    assertThat(event.storeId()).isNull();
    assertThat(event.cashRegisterId()).isNull();
    assertThat(event.requestId()).isNull();
    assertThat(event.ip()).as("sem conexão não há IP de origem").isNull();
    assertThat(event.action()).isEqualTo("LOGIN_LOCKED");
  }

  /**
   * O log é append-only para a aplicação; o teste, conectado como dono das tabelas, remove o que
   * ele mesmo comitou para não sujar as execuções seguintes.
   */
  @AfterEach
  void removeEventsCreatedByThisRun() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("delete from audit_events where entity_id = ?")) {
      for (UUID entityId : recordedEntities) {
        statement.setObject(1, entityId);
        statement.executeUpdate();
      }
    }
    recordedEntities.clear();
  }

  /** Id do alvo do evento; único por teste para nada depender de contar o log inteiro. */
  private UUID newEntityId() {
    UUID entityId = UUID.randomUUID();
    recordedEntities.add(entityId);
    return entityId;
  }

  /** Valores conhecidos do contexto de uma requisição TUI, para conferir o evento campo a campo. */
  private record Actor(
      UUID userId,
      String username,
      UUID sessionId,
      UUID storeId,
      UUID cashRegisterId,
      String requestId,
      InetAddress ip) {

    Actor() {
      this(
          UUID.randomUUID(),
          ACTOR,
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID().toString(),
          IP);
    }
  }

  /** Preenche o contexto como o filtro de autenticação faria para uma sessão TUI (passo 302). */
  private void fillContext(Actor actor) {
    operationContext.fill(
        actor.userId(),
        actor.username(),
        actor.sessionId(),
        actor.storeId(),
        actor.cashRegisterId(),
        actor.requestId(),
        actor.ip(),
        OperationSource.TUI);
  }

  /**
   * Ativa o request context na thread do teste — fora de um request HTTP ninguém o ativa — e o
   * desfaz no fim; se o framework já tiver um ativo, usa o dele sem mexer.
   */
  private void inRequestContext(Runnable action) {
    ManagedContext requestContext = Arc.container().requestContext();
    boolean activated = !requestContext.isActive();
    if (activated) {
      requestContext.activate();
    }
    try {
      action.run();
    } finally {
      if (activated) {
        requestContext.terminate();
      }
    }
  }

  /** Evento como o banco o guardou; {@code details} e {@code ip} vêm no formato textual do PG. */
  private record Event(
      OffsetDateTime occurredAt,
      String storeId,
      String actorUserId,
      String actorUsername,
      String authSessionId,
      String cashSessionId,
      String cashRegisterId,
      String action,
      String entityType,
      String source,
      String requestId,
      String reason,
      String details,
      String ip) {}

  /** Lê o evento do alvo informado; o teste falha se ele não tiver sido gravado. */
  private Event eventOf(UUID entityId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select occurred_at, store_id, actor_user_id, actor_username, auth_session_id,"
                    + " cash_session_id, cash_register_id, action, entity_type, source, request_id,"
                    + " reason, details::text as details, host(ip) as ip"
                    + " from audit_events where entity_id = ?")) {
      statement.setObject(1, entityId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("evento gravado para a entidade %s", entityId).isTrue();
        return new Event(
            resultSet.getObject("occurred_at", OffsetDateTime.class),
            resultSet.getString("store_id"),
            resultSet.getString("actor_user_id"),
            resultSet.getString("actor_username"),
            resultSet.getString("auth_session_id"),
            resultSet.getString("cash_session_id"),
            resultSet.getString("cash_register_id"),
            resultSet.getString("action"),
            resultSet.getString("entity_type"),
            resultSet.getString("source"),
            resultSet.getString("request_id"),
            resultSet.getString("reason"),
            resultSet.getString("details"),
            resultSet.getString("ip"));
      }
    }
  }

  /** Quantas linhas o log guarda para o alvo informado. */
  private long countByEntity(UUID entityId) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("select count(*) from audit_events where entity_id = ?")) {
      statement.setObject(1, entityId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }
  }
}
