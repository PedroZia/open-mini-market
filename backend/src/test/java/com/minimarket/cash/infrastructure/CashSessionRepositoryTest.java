package com.minimarket.cash.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.cash.application.NewCashMovement;
import com.minimarket.cash.application.NewCashSession;
import com.minimarket.cash.domain.CashMovementType;
import com.minimarket.cash.domain.CashSessionStatus;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.users.application.NewUser;
import com.minimarket.users.application.UserStore;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link CashSessionRepository} contra PostgreSQL real (Dev Services). Cada teste
 * roda em transação revertida ao final ({@code @TestTransaction}), então nada do que é criado fica
 * no banco — importante porque as FKs de {@code cash_sessions} e {@code cash_movements} são {@code
 * on delete restrict}.
 *
 * <p>O usuário sai da porta {@link UserStore} e o id do caixa CAIXA-01 (seed da V11) é lido por
 * SQL: o teste não importa infrastructure de outro módulo.
 */
@QuarkusTest
class CashSessionRepositoryTest extends IntegrationTestBase {

  @Inject CashSessionRepository sessionRepository;

  @Inject UserStore userStore;

  @Inject StoreLookup storeLookup;

  @Inject EntityManager entityManager;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  private UUID storeId;

  private UUID registerId;

  @Test
  @TestTransaction
  @DisplayName("insert gera id UUIDv7 e devolve a sessão aberta pelo caixa e pelo id")
  void insertsOpenSession() throws SQLException {
    UUID userId = newUser("caixa.repo.abertura");
    UUID register = registerId();
    Instant openedAt = now();
    UUID id = insertSession(userId, openedAt, "150.00");

    assertThat(id.version()).as("UUIDv7").isEqualTo(7);
    assertThat(sessionRepository.findById(id))
        .hasValueSatisfying(
            found -> {
              assertThat(found.storeId()).isEqualTo(storeId());
              assertThat(found.cashRegisterId()).isEqualTo(register);
              assertThat(found.status()).isEqualTo(CashSessionStatus.OPEN);
              assertThat(found.openedByUserId()).isEqualTo(userId);
              assertThat(found.openedAt()).isEqualTo(openedAt);
              assertThat(found.openingAmount()).isEqualByComparingTo("150.00");
              assertThat(found.closedByUserId()).isNull();
              assertThat(found.closedAt()).isNull();
              assertThat(found.countedAmount()).isNull();
              assertThat(found.expectedAmount()).isNull();
              assertThat(found.differenceAmount()).isNull();
              assertThat(found.closingNotes()).isNull();
              assertThat(found.createdAt()).isNotNull();
              assertThat(found.updatedAt()).isNotNull();
              assertThat(found.version()).isZero();
            });

    assertThat(sessionRepository.findOpenByRegister(register))
        .hasValueSatisfying(found -> assertThat(found.id()).isEqualTo(id));
    assertThat(sessionRepository.findOpenByRegister(UUID.randomUUID())).isEmpty();
    assertThat(sessionRepository.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("findById enxerga a sessão fechada; findOpenByRegister só a aberta")
  void findsClosedSessionByIdOnly() throws SQLException {
    UUID userId = newUser("caixa.repo.historico");
    UUID register = registerId();
    UUID id = insertSession(userId, now(), "100.00");
    closeSession(id);

    assertThat(sessionRepository.findById(id))
        .hasValueSatisfying(
            found -> {
              assertThat(found.status()).isEqualTo(CashSessionStatus.CLOSED);
              assertThat(found.closedAt()).isNotNull();
            });
    assertThat(sessionRepository.findOpenByRegister(register))
        .as("a fechada não ocupa mais o caixa")
        .isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("insertMovement grava os campos e sumByType soma os valores assinados por tipo")
  void insertsMovementsAndSumsByType() throws SQLException {
    UUID userId = newUser("caixa.repo.movimentos");
    UUID sessionId = insertSession(userId, now(), "100.00");
    UUID saleReference = UUID.randomUUID();
    Instant saleCreatedAt = now();
    UUID saleId =
        insertMovement(
            new NewCashMovement(
                storeId(),
                sessionId,
                CashMovementType.SALE,
                new BigDecimal("30.00"),
                "CASH",
                "SALE",
                saleReference,
                null,
                userId,
                saleCreatedAt));
    insertMovement(
        new NewCashMovement(
            storeId(),
            sessionId,
            CashMovementType.OPENING,
            new BigDecimal("100.00"),
            null,
            null,
            null,
            "fundo de troco",
            userId,
            now()));
    insertMovement(
        new NewCashMovement(
            storeId(),
            sessionId,
            CashMovementType.SALE,
            new BigDecimal("20.50"),
            "PIX",
            "SALE",
            UUID.randomUUID(),
            null,
            userId,
            now()));
    insertMovement(
        new NewCashMovement(
            storeId(),
            sessionId,
            CashMovementType.SUPPLY,
            new BigDecimal("50.00"),
            null,
            null,
            null,
            "reforço de troco",
            userId,
            now()));
    insertMovement(
        new NewCashMovement(
            storeId(),
            sessionId,
            CashMovementType.WITHDRAWAL,
            new BigDecimal("-40.00"),
            null,
            null,
            null,
            "sangria",
            userId,
            now()));

    assertThat(saleId.version()).as("UUIDv7").isEqualTo(7);
    Map<CashMovementType, BigDecimal> totals = sessionRepository.sumByType(sessionId);
    assertThat(totals).containsOnlyKeys(CashMovementType.values());
    assertThat(totals.get(CashMovementType.OPENING)).isEqualByComparingTo("100.00");
    assertThat(totals.get(CashMovementType.SALE)).as("30.00 + 20.50").isEqualByComparingTo("50.50");
    assertThat(totals.get(CashMovementType.SUPPLY)).isEqualByComparingTo("50.00");
    assertThat(totals.get(CashMovementType.WITHDRAWAL))
        .as("sangria entra negativa")
        .isEqualByComparingTo("-40.00");
    assertThat(sessionRepository.sumByType(UUID.randomUUID())).isEmpty();

    CashMovementEntity stored = entityManager.find(CashMovementEntity.class, saleId);
    assertThat(stored.getStoreId()).isEqualTo(storeId());
    assertThat(stored.getCashSessionId()).isEqualTo(sessionId);
    assertThat(stored.getMovementType()).isEqualTo(CashMovementType.SALE);
    assertThat(stored.getAmount()).isEqualByComparingTo("30.00");
    assertThat(stored.getPaymentMethod()).isEqualTo("CASH");
    assertThat(stored.getReferenceType()).isEqualTo("SALE");
    assertThat(stored.getReferenceId()).isEqualTo(saleReference);
    assertThat(stored.getReason()).isNull();
    assertThat(stored.getCreatedByUserId()).isEqualTo(userId);
    assertThat(stored.getCreatedAt())
        .as("o instante vem do caso de uso, não do now() do JPA")
        .isEqualTo(saleCreatedAt);
  }

  /** Insere a sessão pelo repositório e limpa o contexto: o que o teste lê depois vem do banco. */
  private UUID insertSession(UUID userId, Instant openedAt, String openingAmount)
      throws SQLException {
    UUID id =
        sessionRepository.insert(
            new NewCashSession(
                storeId(), registerId(), userId, openedAt, new BigDecimal(openingAmount)));
    entityManager.flush();
    entityManager.clear();
    return id;
  }

  /** Insere o movimento pelo repositório e limpa o contexto. */
  private UUID insertMovement(NewCashMovement movement) {
    UUID id = sessionRepository.insertMovement(movement);
    entityManager.flush();
    entityManager.clear();
    return id;
  }

  /**
   * Fecha a sessão direto na entidade (o caso de uso de fechamento é o passo 611): o teste isola a
   * leitura do histórico por id da leitura da sessão aberta por caixa.
   */
  private void closeSession(UUID id) {
    entityManager
        .createQuery(
            "update CashSessionEntity s set s.status = :status, s.closedAt = :closedAt"
                + " where s.id = :id")
        .setParameter("status", CashSessionStatus.CLOSED)
        .setParameter("closedAt", now())
        .setParameter("id", id)
        .executeUpdate();
    entityManager.flush();
    entityManager.clear();
  }

  private UUID newUser(String username) {
    return userStore.insert(new NewUser(username, "Operador de caixa", "hash", "ACTIVE"));
  }

  /** Instante truncado ao microssegundo que o {@code timestamptz} guarda. */
  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  /** Id da loja configurada: a porta {@code StoreLookup} devolve o id desde o passo 204a. */
  private UUID storeId() {
    if (storeId == null) {
      storeId =
          storeLookup
              .findByCode(defaultStoreCode)
              .orElseThrow(() -> new IllegalStateException("loja do seed da V1 ausente"))
              .id();
    }
    return storeId;
  }

  /** Id do caixa semeado pela V11; a leitura é por SQL porque não há porta de caixa ainda. */
  private UUID registerId() throws SQLException {
    if (registerId == null) {
      try (Connection connection = dataSource.getConnection();
          PreparedStatement statement =
              connection.prepareStatement(
                  "select id from cash_registers where code = 'CAIXA-01'")) {
        try (ResultSet resultSet = statement.executeQuery()) {
          assertThat(resultSet.next()).as("seed do CAIXA-01 da V11 presente").isTrue();
          registerId = resultSet.getObject("id", UUID.class);
        }
      }
    }
    return registerId;
  }
}
