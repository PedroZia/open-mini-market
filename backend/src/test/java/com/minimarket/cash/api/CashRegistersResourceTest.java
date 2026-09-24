package com.minimarket.cash.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.IntegrationTestBase;
import com.minimarket.cash.application.CashSessionStore;
import com.minimarket.cash.application.NewCashSession;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.users.application.NewUser;
import com.minimarket.users.application.UserStore;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Listagem de caixas na API (passo 602) contra PostgreSQL real (Dev Services): as rotas de verdade,
 * com o ADMIN da fixture ({@code asAdmin()}). O 401 sem token é do {@code RouteSecurityTest}.
 *
 * <p>A sessão aberta do segundo teste é fixture comitada pela porta {@link CashSessionStore}, em
 * transação própria como no {@code CashSessionLockTest}: o request HTTP roda em outra transação e
 * precisa enxergá-la. O banco é compartilhado e as FKs são {@code restrict}, então o
 * {@code @AfterEach} limpa pelo SQL o que o teste comitou.
 */
@QuarkusTest
class CashRegistersResourceTest extends IntegrationTestBase {

  private static final String PATH = "/api/v1/cash-registers";

  /** Caixa do seed da V11: existe sempre, é a fixture da listagem. */
  private static final String SEEDED_REGISTER_CODE = "CAIXA-01";

  @Inject CashSessionStore cashSessionStore;

  @Inject UserStore userStore;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  private UUID storeId;

  private UUID userId;

  private UUID sessionId;

  private String operatorDisplayName;

  @Test
  @DisplayName("ADMIN lista o CAIXA-01 ativo e fechado: 200 com o contrato da listagem")
  void listsClosedRegister() {
    Response listing = list();

    assertThat(listing.statusCode()).isEqualTo(200);
    assertThat(listing.contentType()).contains("application/json");
    Map<String, Object> register = listedRegister(listing, SEEDED_REGISTER_CODE);
    assertThat(register)
        .as("contrato da listagem: nem storeId nem active vazam")
        .containsOnlyKeys("id", "code", "name", "status", "operatorName");
    assertThat(UUID.fromString((String) register.get("id")).version())
        .as("id é UUIDv7")
        .isEqualTo(7);
    assertThat(register.get("name")).isEqualTo("Caixa 1");
    assertThat(register.get("status"))
        .as("sem sessão aberta o caixa está fechado")
        .isEqualTo("CLOSED");
    assertThat(register.get("operatorName")).as("caixa fechado não tem operador").isNull();
  }

  @Test
  @DisplayName("sessão aberta muda o status do caixa e mostra o operador que abriu")
  void showsOpenSessionAndOperator() {
    seedOpenSession(registerId());

    Response listing = list();

    assertThat(listing.statusCode()).isEqualTo(200);
    Map<String, Object> register = listedRegister(listing, SEEDED_REGISTER_CODE);
    assertThat(register.get("status")).isEqualTo("OPEN");
    assertThat(register.get("operatorName")).isEqualTo(operatorDisplayName);
  }

  /**
   * Sessão aberta no caixa do seed, comitada em transação própria para o request HTTP enxergá-la: o
   * operador sai da porta {@code UserStore} e a sessão, da porta {@code CashSessionStore}.
   */
  private void seedOpenSession(UUID registerId) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    operatorDisplayName = "Operador " + suffix;
    userId =
        callInOwnTransaction(
            () ->
                userStore.insert(
                    new NewUser("caixa.lista." + suffix, operatorDisplayName, "hash", "ACTIVE")));
    sessionId =
        callInOwnTransaction(
            () ->
                cashSessionStore.insert(
                    new NewCashSession(
                        storeId(),
                        registerId,
                        userId,
                        Instant.now().truncatedTo(ChronoUnit.MICROS),
                        new BigDecimal("100.00"))));
  }

  /** Roda a tarefa em transação própria, com o request context do Arc ativado na thread. */
  private static <T> T callInOwnTransaction(Callable<T> work) {
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

  /** GET autenticado como o ADMIN da fixture. */
  private Response list() {
    return asAdmin().when().get(PATH).then().extract().response();
  }

  /** O caixa como a listagem o devolve; falha quando ele não aparece. */
  private static Map<String, Object> listedRegister(Response listing, String code) {
    List<Map<String, Object>> items = listing.jsonPath().getList("$");
    return items.stream()
        .filter(item -> code.equals(item.get("code")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("caixa %s não apareceu na listagem".formatted(code)));
  }

  /** Id do caixa do seed lido da própria listagem: é a API quem o expõe. */
  private UUID registerId() {
    Map<String, Object> register = listedRegister(list(), SEEDED_REGISTER_CODE);
    return UUID.fromString((String) register.get("id"));
  }

  /** Id da loja configurada: a porta {@code StoreLookup} devolve o id desde o passo 204a. */
  private UUID storeId() {
    if (storeId == null) {
      storeId =
          callInOwnTransaction(
              () ->
                  storeLookup
                      .findByCode(defaultStoreCode)
                      .orElseThrow(() -> new IllegalStateException("loja do seed da V1 ausente"))
                      .id());
    }
    return storeId;
  }

  /** Remove o que o teste comitou — o banco é compartilhado e as FKs são {@code restrict}. */
  @AfterEach
  void removeCommittedFixture() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      if (sessionId != null) {
        execute(connection, "delete from cash_sessions where id = ?", sessionId);
      }
      if (userId != null) {
        execute(connection, "delete from users where id = ?", userId);
      }
    }
  }

  private static void execute(Connection connection, String sql, UUID id) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      statement.executeUpdate();
    }
  }
}
