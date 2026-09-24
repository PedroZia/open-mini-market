package com.minimarket.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minimarket.shared.application.IdempotencyKeyStore;
import com.minimarket.shared.application.IdempotencyService;
import com.minimarket.shared.application.NewIdempotencyRecord;
import com.minimarket.shared.application.OperationContext;
import com.minimarket.shared.application.StoredIdempotentResponse;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.OperationSource;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link IdempotencyGuard}, sem Quarkus e sem banco: o dublê é da porta {@link
 * IdempotencyKeyStore} e o {@link IdempotencyService} de verdade é montado por construtor, com
 * relógio e TTL fixos, para a expiração gravada ser conferida campo a campo. A persistência real do
 * mecanismo é coberta pelo {@code IdempotencyKeyRepositoryTest}.
 */
class IdempotencyGuardTest {

  private static final Instant NOW = Instant.parse("2026-09-24T13:00:00Z");
  private static final Duration TTL = Duration.ofHours(24);
  private static final UUID USER_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000001");
  private static final UUID OTHER_USER_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000009");
  private static final String KEY = "chave-do-pdv";
  private static final String METHOD = "POST";
  private static final String PATH =
      "/api/v1/cash-registers/0199a2b3-0000-7000-8000-000000000002/open";

  private final FakeIdempotencyKeyStore store = new FakeIdempotencyKeyStore();
  private final OperationContext operationContext = new OperationContext();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final IdempotencyGuard guard = new IdempotencyGuard();

  @BeforeEach
  void setUp() {
    operationContext.fill(
        USER_ID,
        "operador.teste",
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "req-do-teste",
        null,
        OperationSource.API);
    guard.idempotencyService = new IdempotencyService(store, Clock.fixed(NOW, ZoneOffset.UTC), TTL);
    guard.operationContext = operationContext;
    guard.objectMapper = objectMapper;
  }

  @Test
  @DisplayName("sem Idempotency-Key (ou em branco) é 400 sem executar a ação")
  void rejectsMissingKey() {
    for (String key : Arrays.asList(null, "", "   ")) {
      AtomicBoolean called = new AtomicBoolean();
      assertThatThrownBy(() -> guard.execute(key, METHOD, PATH, payload(), trackingAction(called)))
          .isInstanceOfSatisfying(
              BusinessException.class,
              error -> assertThat(error.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REQUIRED));
      assertThat(called).as("a ação nem é chamada sem chave").isFalse();
    }
    assertThat(store.inserted).isEmpty();
  }

  @Test
  @DisplayName("primeira chamada: executa a ação, devolve a resposta e grava o registro com o TTL")
  void recordsFirstCall() throws Exception {
    Response actionResponse = created(sessionBody());

    Response response = guard.execute(KEY, METHOD, PATH, payload(), () -> actionResponse);

    assertThat(response).isSameAs(actionResponse);
    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getHeaderString(IdempotencyGuard.REPLAYED_HEADER)).isNull();

    assertThat(store.inserted).hasSize(1);
    NewIdempotencyRecord record = store.inserted.getFirst();
    assertThat(record.key()).isEqualTo(KEY);
    assertThat(record.userId()).isEqualTo(USER_ID);
    assertThat(record.method()).isEqualTo(METHOD);
    assertThat(record.path()).isEqualTo(PATH);
    assertThat(record.requestHash())
        .isEqualTo(sha256Hex(objectMapper.writeValueAsString(payload())));
    assertThat(record.statusCode()).isEqualTo(201);
    assertThat(record.responseBody()).isEqualTo(objectMapper.writeValueAsString(sessionBody()));
    assertThat(store.expiresAt)
        .as("expires_at = Clock + minimarket.idempotency.ttl")
        .isEqualTo(NOW.plus(TTL));
  }

  @Test
  @DisplayName("chave repetida: replay com status e corpo gravados, sem rodar a ação")
  void replaysStoredResponse() {
    String storedBody = "{\"id\":\"0199a2b3-0000-7000-8000-000000000002\",\"status\":\"OPEN\"}";
    store.put(KEY, storedResponse(201, storedBody, requestHash()));

    Response response =
        guard.execute(
            KEY,
            METHOD,
            PATH,
            payload(),
            () -> {
              throw new AssertionError("a ação não deveria rodar no replay");
            });

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getMediaType()).isEqualTo(MediaType.APPLICATION_JSON_TYPE);
    assertThat(response.getHeaderString(IdempotencyGuard.REPLAYED_HEADER)).isEqualTo("true");
    assertThat(new String((byte[]) response.getEntity(), StandardCharsets.UTF_8))
        .as("bytes do JSON gravado, sem re-serializar")
        .isEqualTo(storedBody);
    assertThat(store.inserted).isEmpty();
  }

  @Test
  @DisplayName("mesma chave com hash diferente é 409 IDEMPOTENCY_KEY_REUSED")
  void rejectsDifferentHash() {
    store.put(KEY, storedResponse(201, "{}", "outro-hash"));
    AtomicBoolean called = new AtomicBoolean();

    assertThatThrownBy(() -> guard.execute(KEY, METHOD, PATH, payload(), trackingAction(called)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    assertThat(called).as("o conflito é detectado antes de executar").isFalse();
  }

  @Test
  @DisplayName("mesma chave com outro usuário é 409 IDEMPOTENCY_KEY_REUSED")
  void rejectsDifferentUser() {
    store.put(KEY, storedResponse(201, "{}", requestHash(), OTHER_USER_ID));
    AtomicBoolean called = new AtomicBoolean();

    assertThatThrownBy(() -> guard.execute(KEY, METHOD, PATH, payload(), trackingAction(called)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    assertThat(called).isFalse();
  }

  @Test
  @DisplayName("corrida no record: perde a PK, relê e devolve o replay do vencedor")
  void replaysWinnerOnInsertRace() throws Exception {
    Map<String, Object> winnerBody = new LinkedHashMap<>();
    winnerBody.put("id", "0199a2b3-0000-7000-8000-00000000000a");
    String winnerJson = objectMapper.writeValueAsString(winnerBody);
    store.loseTo(KEY, storedResponse(201, winnerJson, requestHash()));

    Response response = guard.execute(KEY, METHOD, PATH, payload(), () -> created(sessionBody()));

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getHeaderString(IdempotencyGuard.REPLAYED_HEADER)).isEqualTo("true");
    assertThat(new String((byte[]) response.getEntity(), StandardCharsets.UTF_8))
        .as("resposta do vencedor, não a que esta chamada produziu")
        .isEqualTo(winnerJson);
    assertThat(store.inserted).isEmpty();
    assertThat(store.findCalls)
        .as("leitura antes da ação + releitura após perder a PK")
        .isEqualTo(2);
  }

  @Test
  @DisplayName("resposta com status >= 400 não é gravada: retry de erro reexecuta")
  void doesNotRecordErrorResponse() {
    Response response =
        guard.execute(
            KEY, METHOD, PATH, payload(), () -> Response.status(409).entity("erro").build());

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(store.inserted).isEmpty();
  }

  @Test
  @DisplayName("rota idempotente sem usuário no contexto é erro de fiação, não 400")
  void rejectsMissingUser() {
    guard.operationContext = new OperationContext();

    assertThatThrownBy(
            () -> guard.execute(KEY, METHOD, PATH, payload(), () -> created(sessionBody())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OperationContext");
    assertThat(store.inserted).isEmpty();
  }

  /** Ação que marca se rodou, para os cenários em que ela não deveria ser chamada. */
  private static Supplier<Response> trackingAction(AtomicBoolean called) {
    return () -> {
      called.set(true);
      return created(sessionBody());
    };
  }

  private static Response created(Object body) {
    return Response.status(201).entity(body).build();
  }

  private static Map<String, Object> payload() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("openingAmount", new BigDecimal("150.00"));
    return payload;
  }

  private static Map<String, Object> sessionBody() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", "0199a2b3-0000-7000-8000-000000000002");
    body.put("status", "OPEN");
    return body;
  }

  private StoredIdempotentResponse storedResponse(int statusCode, String body, String hash) {
    return storedResponse(statusCode, body, hash, USER_ID);
  }

  private static StoredIdempotentResponse storedResponse(
      int statusCode, String body, String hash, UUID userId) {
    return new StoredIdempotentResponse(statusCode, body, hash, METHOD, PATH, userId);
  }

  private String requestHash() {
    try {
      return sha256Hex(objectMapper.writeValueAsString(payload()));
    } catch (Exception exception) {
      throw new IllegalStateException("payload do teste não serializa", exception);
    }
  }

  private static String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 não disponível na JVM", exception);
    }
  }

  /**
   * Dublê da porta: guarda o que foi inserido (com a expiração que o serviço decidiu) e permite
   * armar a corrida — {@link #loseTo} esconde o vencedor até o insert desta chamada perder a PK,
   * como acontece no banco.
   */
  private static final class FakeIdempotencyKeyStore implements IdempotencyKeyStore {

    private final Map<String, StoredIdempotentResponse> stored = new HashMap<>();
    private final List<NewIdempotencyRecord> inserted = new ArrayList<>();
    private Instant expiresAt;
    private StoredIdempotentResponse invisibleWinner;
    private int findCalls;

    void put(String key, StoredIdempotentResponse response) {
      stored.put(key, response);
    }

    void loseTo(String key, StoredIdempotentResponse winner) {
      stored.remove(key);
      invisibleWinner = winner;
    }

    @Override
    public Optional<StoredIdempotentResponse> find(String key) {
      findCalls++;
      return Optional.ofNullable(stored.get(key));
    }

    @Override
    public void insert(NewIdempotencyRecord record, Instant expiresAt) {
      this.expiresAt = expiresAt;
      if (invisibleWinner != null) {
        stored.put(record.key(), invisibleWinner);
        invisibleWinner = null;
        throw new ConflictException(
            ErrorCode.IDEMPOTENCY_KEY_REUSED, "chave de idempotência já utilizada");
      }
      inserted.add(record);
      stored.put(
          record.key(),
          new StoredIdempotentResponse(
              record.statusCode(),
              record.responseBody(),
              record.requestHash(),
              record.method(),
              record.path(),
              record.userId()));
    }
  }
}
