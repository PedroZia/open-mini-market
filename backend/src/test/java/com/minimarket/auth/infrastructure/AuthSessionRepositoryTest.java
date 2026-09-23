package com.minimarket.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.auth.application.NewAuthSession;
import com.minimarket.auth.domain.SessionClient;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.users.application.NewUser;
import com.minimarket.users.application.UserStore;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link AuthSessionRepository} contra PostgreSQL real (Dev Services). Cada teste
 * roda em transação revertida ao final ({@code @TestTransaction}), então nada do que é criado fica
 * no banco — importante porque as FKs de {@code auth_sessions} são {@code on delete restrict}.
 *
 * <p>O usuário sai da porta {@link UserStore} e o id da loja MATRIZ vem do {@link
 * IntegrationTestBase#dataSource}: o teste não importa infrastructure de outro módulo.
 */
@QuarkusTest
class AuthSessionRepositoryTest extends IntegrationTestBase {

  @Inject AuthSessionRepository sessionRepository;

  @Inject UserStore userStore;

  @Inject StoreLookup storeLookup;

  @Inject EntityManager entityManager;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  private UUID storeId;

  @Test
  @TestTransaction
  @DisplayName("insert gera id UUIDv7, preenche created_at/last_seen_at e persiste o ip como inet")
  void inserts() throws Exception {
    UUID userId = newUser("sessao.insert");
    UUID cashRegisterId = UUID.randomUUID();
    UUID store = storeId();
    Instant lastSeenAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Instant expiresAt = expiresAt();
    InetAddress ip = InetAddress.getByName("192.168.0.10");
    AuthSessionEntity session =
        new AuthSessionEntity(
            userId,
            "hash-insert",
            "TUI",
            store,
            cashRegisterId,
            ip,
            "terminal/1.0",
            lastSeenAt,
            expiresAt);

    UUID id = sessionRepository.insert(session);
    assertThat(id).isNotNull();
    assertThat(session.getVersion()).isZero();
    entityManager.flush();
    entityManager.clear();

    assertThat(sessionRepository.findById(id))
        .hasValueSatisfying(
            found -> {
              assertThat(found.getUserId()).isEqualTo(userId);
              assertThat(found.getTokenHash()).isEqualTo("hash-insert");
              assertThat(found.getClient()).isEqualTo("TUI");
              assertThat(found.getStoreId()).isEqualTo(store);
              assertThat(found.getCashRegisterId()).isEqualTo(cashRegisterId);
              assertThat(found.getIp()).isEqualTo(ip);
              assertThat(found.getUserAgent()).isEqualTo("terminal/1.0");
              assertThat(found.getCreatedAt()).isNotNull();
              assertThat(found.getLastSeenAt()).isEqualTo(lastSeenAt);
              assertThat(found.getExpiresAt()).isEqualTo(expiresAt);
              assertThat(found.getRevokedAt()).isNull();
              assertThat(found.getRevokedReason()).isNull();
            });

    // O valor no banco é inet de verdade (não texto): o Postgres vê a coluna como inet e devolve o
    // endereço normalizado na notação address/máscara. A leitura como InetAddress já tira a
    // máscara.
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select ip::text, pg_typeof(ip)::text from auth_sessions where id = cast(? as"
                    + " uuid)")) {
      statement.setString(1, id.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.getString(1)).isEqualTo("192.168.0.10/32");
        assertThat(resultSet.getString(2)).isEqualTo("inet");
      }
    }
  }

  @Test
  @TestTransaction
  @DisplayName(
      "insert aceita ip, caixa e user_agent nulos e permite várias sessões do mesmo usuário")
  void insertsWithOptionalFieldsMissing() throws Exception {
    UUID userId = newUser("sessao.opcionais");
    UUID first = insert(session(userId, "hash-opcional-1"));
    UUID second = insert(session(userId, "hash-opcional-2"));

    assertThat(sessionRepository.findById(first))
        .hasValueSatisfying(
            found -> {
              assertThat(found.getIp()).isNull();
              assertThat(found.getCashRegisterId()).isNull();
              assertThat(found.getUserAgent()).isNull();
            });
    assertThat(sessionRepository.findActiveByTokenHash("hash-opcional-2"))
        .hasValueSatisfying(found -> assertThat(found.getId()).isEqualTo(second));
  }

  @Test
  @TestTransaction
  @DisplayName("insert(NewAuthSession) da porta traduz o client e mantém o hash e os instantes")
  void insertsThroughStorePort() throws Exception {
    UUID userId = newUser("sessao.porta");
    NewAuthSession session =
        new NewAuthSession(
            userId,
            "hash-porta",
            SessionClient.WEB,
            storeId(),
            null,
            null,
            "web/1.0",
            Instant.now().truncatedTo(ChronoUnit.MICROS),
            expiresAt());

    UUID id = sessionRepository.insert(session);
    entityManager.flush();
    entityManager.clear();

    assertThat(sessionRepository.findById(id))
        .hasValueSatisfying(
            found -> {
              assertThat(found.getUserId()).isEqualTo(userId);
              assertThat(found.getTokenHash()).isEqualTo("hash-porta");
              assertThat(found.getClient()).isEqualTo("WEB");
              assertThat(found.getStoreId()).isEqualTo(storeId());
              assertThat(found.getUserAgent()).isEqualTo("web/1.0");
              assertThat(found.getLastSeenAt()).isEqualTo(session.lastSeenAt());
              assertThat(found.getExpiresAt()).isEqualTo(session.expiresAt());
            });
  }

  @Test
  @TestTransaction
  @DisplayName(
      "findActiveByTokenHash acha a não revogada e ignora a revogada e o hash desconhecido")
  void findsActiveByTokenHash() throws Exception {
    UUID userId = newUser("sessao.hash");
    UUID active = insert(session(userId, "hash-ativo"));
    UUID revoked = insert(session(userId, "hash-revogado"));
    sessionRepository.revoke(revoked, "LOGOUT", Instant.now().truncatedTo(ChronoUnit.MICROS));
    // Sessão expirada continua "ativa" para o repositório: expiração é do caso de uso (passo 204).
    UUID expired = insert(session(userId, "hash-expirado", Instant.now().minusSeconds(60)));
    entityManager.flush();
    entityManager.clear();

    assertThat(sessionRepository.findActiveByTokenHash("hash-ativo"))
        .hasValueSatisfying(found -> assertThat(found.getId()).isEqualTo(active));
    assertThat(sessionRepository.findActiveByTokenHash("hash-revogado")).isEmpty();
    assertThat(sessionRepository.findActiveByTokenHash("hash-expirado"))
        .hasValueSatisfying(found -> assertThat(found.getId()).isEqualTo(expired));
    assertThat(sessionRepository.findActiveByTokenHash("hash-inexistente")).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("findById devolve a sessão revogada também, e vazio para id desconhecido")
  void findsById() throws Exception {
    UUID userId = newUser("sessao.por-id");
    UUID id = insert(session(userId, "hash-por-id"));
    sessionRepository.revoke(id, "LOGOUT", Instant.now().truncatedTo(ChronoUnit.MICROS));
    entityManager.flush();
    entityManager.clear();

    assertThat(sessionRepository.findById(id))
        .hasValueSatisfying(
            found -> {
              assertThat(found.getTokenHash()).isEqualTo("hash-por-id");
              assertThat(found.getRevokedAt()).isNotNull();
              assertThat(found.isActive()).isFalse();
            });
    assertThat(sessionRepository.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("touchLastSeen grava o instante e ignora sessão revogada e id desconhecido")
  void touchesLastSeen() throws Exception {
    UUID userId = newUser("sessao.touch");
    UUID active = insert(session(userId, "hash-touch-ativo"));
    UUID revoked = insert(session(userId, "hash-touch-revogado"));
    sessionRepository.revoke(revoked, "LOGOUT", Instant.now().truncatedTo(ChronoUnit.MICROS));
    entityManager.flush();
    entityManager.clear();
    Instant seenAtBefore = sessionRepository.findById(revoked).orElseThrow().getLastSeenAt();

    Instant seenAt = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
    sessionRepository.touchLastSeen(active, seenAt);
    sessionRepository.touchLastSeen(revoked, seenAt);
    sessionRepository.touchLastSeen(UUID.randomUUID(), seenAt);
    entityManager.flush();
    entityManager.clear();

    assertThat(sessionRepository.findById(active))
        .hasValueSatisfying(
            found -> {
              assertThat(found.getLastSeenAt()).isEqualTo(seenAt);
              assertThat(found.getVersion()).isEqualTo(1);
            });
    assertThat(sessionRepository.findById(revoked))
        .hasValueSatisfying(found -> assertThat(found.getLastSeenAt()).isEqualTo(seenAtBefore));
  }

  @Test
  @TestTransaction
  @DisplayName("revoke grava instante e motivo uma vez: revogar de novo mantém os originais")
  void revokesOnce() throws Exception {
    UUID userId = newUser("sessao.revoke");
    UUID id = insert(session(userId, "hash-revoke"));
    Instant revokedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    sessionRepository.revoke(id, "LOGOUT", revokedAt);
    sessionRepository.revoke(UUID.randomUUID(), "LOGOUT", revokedAt);
    entityManager.flush();
    entityManager.clear();

    sessionRepository.revoke(id, "ADMIN", revokedAt.plusSeconds(30));
    entityManager.flush();
    entityManager.clear();

    assertThat(sessionRepository.findById(id))
        .hasValueSatisfying(
            found -> {
              assertThat(found.getRevokedAt()).isEqualTo(revokedAt);
              assertThat(found.getRevokedReason()).isEqualTo("LOGOUT");
            });
  }

  @Test
  @TestTransaction
  @DisplayName("revokeAllByUser revoga só as ativas do usuário e devolve quantas revogou")
  void revokesAllByUser() throws Exception {
    UUID userId = newUser("sessao.revoke-all");
    UUID otherUserId = newUser("sessao.revoke-all-outro");
    insert(session(userId, "hash-all-1"));
    insert(session(userId, "hash-all-2"));
    UUID alreadyRevoked = insert(session(userId, "hash-all-3"));
    insert(session(otherUserId, "hash-all-4"));
    Instant originalRevoke = Instant.now().truncatedTo(ChronoUnit.MICROS);
    sessionRepository.revoke(alreadyRevoked, "LOGOUT", originalRevoke);
    entityManager.flush();
    entityManager.clear();

    Instant revokedAt = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
    assertThat(sessionRepository.revokeAllByUser(userId, "ADMIN", revokedAt)).isEqualTo(2);
    entityManager.flush();
    entityManager.clear();

    assertThat(sessionRepository.findActiveByTokenHash("hash-all-1")).isEmpty();
    assertThat(sessionRepository.findActiveByTokenHash("hash-all-2")).isEmpty();
    // A sessão do outro usuário continua ativa e a que já estava revogada mantém os originais.
    assertThat(sessionRepository.findActiveByTokenHash("hash-all-4")).isPresent();
    assertThat(sessionRepository.findById(alreadyRevoked))
        .hasValueSatisfying(
            found -> {
              assertThat(found.getRevokedAt()).isEqualTo(originalRevoke);
              assertThat(found.getRevokedReason()).isEqualTo("LOGOUT");
            });
    // Sem sessão ativa restante (e id desconhecido), a revogação em lote não revoga nada.
    assertThat(sessionRepository.revokeAllByUser(userId, "ADMIN", revokedAt)).isZero();
    assertThat(sessionRepository.revokeAllByUser(UUID.randomUUID(), "ADMIN", revokedAt)).isZero();
  }

  @Test
  @TestTransaction
  @DisplayName("listActiveByUser lista só as vivas do usuário, da mais recente para a mais antiga")
  void listsActiveByUser() throws Exception {
    UUID userId = newUser("sessao.list");
    UUID otherUserId = newUser("sessao.list-outro");
    UUID oldest = insert(session(userId, "hash-list-1"));
    UUID middle = insert(session(userId, "hash-list-2"));
    UUID newest = insert(session(userId, "hash-list-3"));
    UUID revoked = insert(session(userId, "hash-list-4"));
    insert(session(otherUserId, "hash-list-5"));

    Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS);
    sessionRepository.touchLastSeen(oldest, base.minusSeconds(120));
    sessionRepository.touchLastSeen(middle, base.minusSeconds(60));
    sessionRepository.touchLastSeen(newest, base);
    sessionRepository.revoke(revoked, "LOGOUT", base);
    entityManager.flush();
    entityManager.clear();

    assertThat(sessionRepository.listActiveByUser(userId))
        .extracting(AuthSessionEntity::getId)
        .containsExactly(newest, middle, oldest);
    assertThat(sessionRepository.listActiveByUser(UUID.randomUUID())).isEmpty();
  }

  private UUID newUser(String username) {
    return userStore.insert(new NewUser(username, "Usuário " + username, "hash", "ACTIVE"));
  }

  private AuthSessionEntity session(UUID userId, String tokenHash) throws Exception {
    return session(userId, tokenHash, expiresAt());
  }

  private AuthSessionEntity session(UUID userId, String tokenHash, Instant expiresAt)
      throws Exception {
    return new AuthSessionEntity(
        userId,
        tokenHash,
        "TUI",
        storeId(),
        null,
        null,
        null,
        Instant.now().truncatedTo(ChronoUnit.MICROS),
        expiresAt);
  }

  /** Sessão de 12 h (§6.2), truncada ao microssegundo que o {@code timestamptz} guarda. */
  private static Instant expiresAt() {
    return Instant.now().plus(12, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
  }

  private UUID insert(AuthSessionEntity session) {
    UUID id = sessionRepository.insert(session);
    entityManager.flush();
    entityManager.clear();
    return id;
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
}
