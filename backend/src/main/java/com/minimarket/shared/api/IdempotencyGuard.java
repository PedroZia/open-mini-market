package com.minimarket.shared.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minimarket.shared.application.IdempotencyService;
import com.minimarket.shared.application.NewIdempotencyRecord;
import com.minimarket.shared.application.OperationContext;
import com.minimarket.shared.application.StoredIdempotentResponse;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Idempotência das operações de dinheiro/estoque no nível HTTP (§8, passo 607a). Reutilizável: o
 * endpoint chama {@link #execute} com a chave do header, o método, o caminho, o corpo da requisição
 * e a ação que produz a resposta; o guard cuida do resto.
 *
 * <p>Header {@code Idempotency-Key} ausente ou vazio é 400 {@code IDEMPOTENCY_KEY_REQUIRED} — a
 * operação é idempotente por contrato e o cliente precisa mandar a chave. O ator vem do {@link
 * OperationContext}: rota idempotente é autenticada, então usuário nulo é erro de fiação (500), não
 * 400. O hash da requisição é SHA-256 em hex do corpo serializado com o {@link ObjectMapper} da
 * aplicação.
 *
 * <p>Chave já usada: replay <em>somente</em> quando usuário, método, caminho e hash batem — a
 * resposta devolvida é a gravada, com o status e os bytes do JSON originais ({@code
 * application/json}) e o header {@code Idempotency-Replayed: true}, sem reexecutar a ação (o {@code
 * Location} do 201 não é regravado; o corpo com o id é o que o cliente precisa). Qualquer
 * divergência — inclusive outro usuário com a mesma chave — é 409 {@code IDEMPOTENCY_KEY_REUSED}.
 *
 * <p>Primeira chamada: executa a ação e grava a resposta <em>só se</em> o status for menor que 400
 * — retry de erro não fica preso na chave. Corpo nulo vira o literal JSON {@code null} (a coluna é
 * {@code jsonb not null}; SQL NULL não vale). A validade gravada ({@code expires_at}, 24 h por
 * configuração) é para a limpeza do passo 1004; o replay não a filtra.
 *
 * <p>Corrida: duas chamadas iguais podem passar pelo {@code find} antes de qualquer uma gravar. O
 * INSERT do perdedor viola a PK e chega como {@code ConflictException(IDEMPOTENCY_KEY_REUSED)};
 * aqui ele relê: se o vencedor tem o mesmo usuário/método/caminho/hash, devolve o replay dele; se
 * não, 409. A ação do perdedor já executou antes desta releitura — a janela entre a ação e o
 * registro é o que a chave não fecha, e cada caso de uso defende o próprio efeito com as
 * invariantes de estado dele (ex.: índice único de sessão aberta do caixa).
 */
@ApplicationScoped
public class IdempotencyGuard {

  /** Header da chave de idempotência (§8). */
  public static final String KEY_HEADER = "Idempotency-Key";

  /** Marca a resposta que veio do registro, sem reexecutar a operação (§8). */
  public static final String REPLAYED_HEADER = "Idempotency-Replayed";

  private static final String SHA_256 = "SHA-256";

  private static final int BAD_REQUEST = 400;

  @Inject IdempotencyService idempotencyService;

  @Inject OperationContext operationContext;

  @Inject ObjectMapper objectMapper;

  /**
   * Executa a operação sob a chave informada, devolvendo o replay quando a chave já foi usada.
   * {@code payload} é o corpo da requisição (nulo quando não há); {@code action} produz a resposta
   * da execução de verdade.
   */
  public Response execute(
      String key, String method, String path, Object payload, Supplier<Response> action) {
    String requiredKey = requireKey(key);
    UUID userId = requireAuthenticatedUser();
    String requestHash = requestHash(payload);

    Optional<StoredIdempotentResponse> stored = idempotencyService.find(requiredKey);
    if (stored.isPresent()) {
      return replayOrConflict(stored.get(), requiredKey, userId, method, path, requestHash);
    }

    Response response = action.get();
    if (response.getStatus() >= BAD_REQUEST) {
      return response;
    }
    return record(requiredKey, userId, method, path, requestHash, response);
  }

  /**
   * Grava a resposta da primeira chamada. Se outra chamada gravou a chave entre o {@code find} e
   * este INSERT, o adaptador traduz a violação de PK; relê fora da transação que morreu e devolve o
   * replay do vencedor (ou o próprio 409, se o registro não bater com esta requisição).
   */
  private Response record(
      String key, UUID userId, String method, String path, String requestHash, Response response) {
    NewIdempotencyRecord record =
        new NewIdempotencyRecord(
            key,
            userId,
            method,
            path,
            requestHash,
            response.getStatus(),
            json(response.getEntity()));
    try {
      idempotencyService.record(record);
      return response;
    } catch (ConflictException conflict) {
      if (conflict.code() != ErrorCode.IDEMPOTENCY_KEY_REUSED) {
        throw conflict;
      }
      StoredIdempotentResponse winner = idempotencyService.find(key).orElseThrow(() -> conflict);
      return replayOrConflict(winner, key, userId, method, path, requestHash);
    }
  }

  /** Replay se a chave é da mesma requisição; qualquer divergência é 409 (§8). */
  private Response replayOrConflict(
      StoredIdempotentResponse stored,
      String key,
      UUID userId,
      String method,
      String path,
      String requestHash) {
    boolean sameRequest =
        stored.userId().equals(userId)
            && stored.method().equals(method)
            && stored.path().equals(path)
            && stored.requestHash().equals(requestHash);
    if (!sameRequest) {
      throw new ConflictException(
          ErrorCode.IDEMPOTENCY_KEY_REUSED,
          "chave de idempotência %s já usada com outra requisição".formatted(key));
    }
    return replay(stored);
  }

  /**
   * Resposta do replay: status e bytes do JSON gravados, sem re-serializar (o jsonb pode ter
   * normalizado espaços e ordem das chaves — os bytes valem como corpo original).
   */
  private static Response replay(StoredIdempotentResponse stored) {
    return Response.status(stored.statusCode())
        .type(MediaType.APPLICATION_JSON_TYPE)
        .header(REPLAYED_HEADER, "true")
        .entity(stored.responseBody().getBytes(StandardCharsets.UTF_8))
        .build();
  }

  /** SHA-256 hex do corpo da requisição, como a coluna {@code request_hash} exige (§5.3). */
  private String requestHash(Object payload) {
    try {
      byte[] digest =
          MessageDigest.getInstance(SHA_256).digest(json(payload).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 não disponível na JVM", exception);
    }
  }

  /**
   * JSON do valor com o mapper da aplicação; {@code null} serializa como o literal {@code null}.
   */
  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("valor não serializável como JSON", exception);
    }
  }

  /** A chave é obrigatória: sem ela não há como reconhecer o retry. */
  private static String requireKey(String key) {
    if (key == null || key.isBlank()) {
      throw new BusinessException(
          ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "cabeçalho Idempotency-Key é obrigatório");
    }
    return key;
  }

  /**
   * Rota idempotente é autenticada: usuário nulo significa filtro/identidade quebrados, não 400.
   */
  private UUID requireAuthenticatedUser() {
    UUID userId = operationContext.userId();
    if (userId == null) {
      throw new IllegalStateException(
          "rota idempotente sem usuário no OperationContext: o filtro da requisição deveria"
              + " preenchê-lo");
    }
    return userId;
  }
}
