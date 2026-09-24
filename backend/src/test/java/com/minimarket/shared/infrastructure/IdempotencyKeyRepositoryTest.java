package com.minimarket.shared.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minimarket.IntegrationTestBase;
import com.minimarket.shared.application.NewIdempotencyRecord;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link IdempotencyKeyRepository} contra PostgreSQL real (Dev Services). Cada teste
 * roda em transação revertida ao final ({@code @TestTransaction}); o usuário da FK é inserido por
 * SQL nativo na própria transação.
 */
@QuarkusTest
class IdempotencyKeyRepositoryTest extends IntegrationTestBase {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String KEY = "chave-do-teste";
  private static final Instant EXPIRES_AT = Instant.parse("2026-09-25T13:00:00Z");
  private static final String BODY =
      "{\"id\":\"0199a2b3-0000-7000-8000-000000000001\",\"status\":\"OPEN\",\"openingAmount\":150.00}";

  @Inject IdempotencyKeyRepository repository;

  @Inject EntityManager entityManager;

  @Test
  @TestTransaction
  @DisplayName("grava o registro com a expiração decidida pela aplicação e o devolve pela chave")
  void insertsAndFinds() {
    UUID userId = insertUser();

    repository.insert(
        new NewIdempotencyRecord(
            KEY, userId, "POST", "/api/v1/cash-registers/x/open", "hash-abc", 201, BODY),
        EXPIRES_AT);
    entityManager.flush();
    entityManager.clear();

    assertThat(repository.find(KEY))
        .hasValueSatisfying(
            found -> {
              assertThat(found.statusCode()).isEqualTo(201);
              assertThat(found.userId()).isEqualTo(userId);
              assertThat(found.method()).isEqualTo("POST");
              assertThat(found.path()).isEqualTo("/api/v1/cash-registers/x/open");
              assertThat(found.requestHash()).isEqualTo("hash-abc");
              assertThat(json(found.responseBody()))
                  .as("jsonb normaliza espaços/ordem; o conteúdo é o mesmo JSON")
                  .isEqualTo(json(BODY));
            });

    IdempotencyKeyEntity raw = entityManager.find(IdempotencyKeyEntity.class, KEY);
    assertThat(raw.getExpiresAt()).isEqualTo(EXPIRES_AT);
    assertThat(raw.getCreatedAt()).isNotNull();

    assertThat(repository.find("chave-inexistente")).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("corpo nulo vira o literal JSON null: a coluna jsonb é not null")
  void storesJsonNullBody() {
    UUID userId = insertUser();

    repository.insert(
        new NewIdempotencyRecord(
            KEY, userId, "POST", "/api/v1/sales/x/complete", "hash", 200, "null"),
        EXPIRES_AT);
    entityManager.flush();
    entityManager.clear();

    assertThat(repository.find(KEY))
        .hasValueSatisfying(
            found ->
                assertThat(found.responseBody()).as("JSON null, não SQL NULL").isEqualTo("null"));
  }

  @Test
  @TestTransaction
  @DisplayName("chave repetida viola a PK e vira ConflictException(IDEMPOTENCY_KEY_REUSED)")
  void translatesDuplicateKey() {
    UUID userId = insertUser();
    repository.insert(record(userId, "hash-1"), EXPIRES_AT);
    entityManager.flush();
    // Sem o clear, o Hibernate recusa no próprio contexto de persistência (EntityExistsException);
    // no fluxo real a chave do vencedor veio de outra transação, então quem recusa é o banco.
    entityManager.clear();

    assertThatThrownBy(() -> repository.insert(record(userId, "hash-2"), EXPIRES_AT))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
  }

  private NewIdempotencyRecord record(UUID userId, String requestHash) {
    return new NewIdempotencyRecord(
        KEY, userId, "POST", "/api/v1/cash-registers/x/open", requestHash, 201, BODY);
  }

  /** Usuário da FK: papel de operador de teste, vivo só na transação revertida do teste. */
  private UUID insertUser() {
    Object id =
        entityManager
            .createNativeQuery(
                "insert into users (id, username, password_hash, display_name, status)"
                    + " values (uuidv7(), 'idempotencia.teste', 'hash', 'Operador de teste',"
                    + " 'ACTIVE') returning id")
            .getSingleResult();
    return UUID.fromString(id.toString());
  }

  private static JsonNode json(String value) {
    try {
      return JSON.readTree(value);
    } catch (Exception exception) {
      throw new IllegalStateException("JSON inválido no cenário do teste", exception);
    }
  }
}
