package com.minimarket.shared.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.shared.infrastructure.IdempotencyKeyEntity;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link IdempotencyService} contra PostgreSQL real (Dev Services): prova a fiação de
 * verdade do bean (store, {@code Clock} e {@code minimarket.idempotency.ttl} por configuração) e o
 * {@code expires_at} que a limpeza do passo 1004 vai varrer. Cada teste roda em transação revertida
 * ao final ({@code @TestTransaction}).
 */
@QuarkusTest
class IdempotencyServiceTest extends IntegrationTestBase {

  private static final String KEY = "chave-do-servico";
  private static final String PATH = "/api/v1/cash-registers/x/open";

  @Inject IdempotencyService idempotencyService;

  @Inject EntityManager entityManager;

  @Test
  @TestTransaction
  @DisplayName("record grava o registro com expires_at = agora + TTL configurado (24 h)")
  void recordsWithConfiguredTtl() {
    UUID userId = insertUser();
    Instant before = Instant.now();

    idempotencyService.record(
        new NewIdempotencyRecord(
            KEY, userId, "POST", PATH, "hash-do-corpo", 201, "{\"id\":\"x\"}"));

    Instant expiresAt = entityManager.find(IdempotencyKeyEntity.class, KEY).getExpiresAt();
    assertThat(expiresAt)
        .as("TTL de minimarket.idempotency.ttl (24 h), decidido pelo Clock da aplicação")
        .isBetween(before.plus(Duration.ofHours(24)), Instant.now().plus(Duration.ofHours(24)));
    assertThat(idempotencyService.find(KEY))
        .hasValueSatisfying(
            found -> {
              assertThat(found.statusCode()).isEqualTo(201);
              assertThat(found.userId()).isEqualTo(userId);
              assertThat(found.requestHash()).isEqualTo("hash-do-corpo");
            });
  }

  /** Usuário da FK: papel de operador de teste, vivo só na transação revertida do teste. */
  private UUID insertUser() {
    Object id =
        entityManager
            .createNativeQuery(
                "insert into users (id, username, password_hash, display_name, status)"
                    + " values (uuidv7(), 'idempotencia.servico.teste', 'hash',"
                    + " 'Operador de teste', 'ACTIVE') returning id")
            .getSingleResult();
    return UUID.fromString(id.toString());
  }
}
