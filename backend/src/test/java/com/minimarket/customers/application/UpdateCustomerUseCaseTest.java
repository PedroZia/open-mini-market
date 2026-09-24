package com.minimarket.customers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link UpdateCustomerUseCase}, sem Quarkus e sem banco: a porta é um dublê
 * escrito à mão. O gravador de auditoria também é dublê — o evento é conferido como o caso de uso o
 * entregou, e a gravação de verdade contra o PostgreSQL fica com o teste de API do 502b.
 */
class UpdateCustomerUseCaseTest {

  private static final UUID CUSTOMER_ID = UUID.randomUUID();
  private static final UUID STORE_ID = UUID.randomUUID();
  private static final Instant CREATED_AT = Instant.parse("2026-01-02T03:04:05Z");
  private static final String CURRENT_CPF = "11144477735";
  private static final String OTHER_CPF = "52998224725";

  private final FakeCustomerStore customerStore = new FakeCustomerStore();
  private final FakeAuditRecorder auditRecorder = new FakeAuditRecorder();

  private UpdateCustomerUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new UpdateCustomerUseCase();
    useCase.customerStore = customerStore;
    useCase.auditRecorder = auditRecorder;
  }

  @Test
  @DisplayName("edita os cinco campos normalizados e grava CUSTOMER_UPDATED com o antes/depois")
  void updatesCustomer() {
    CustomerSummary updated =
        useCase.execute(
            command(
                "Ana Souza Lima",
                "529.982.247-25",
                "(21) 98765-4321",
                "ana.lima@exemplo.com",
                "mudou de endereço"));

    assertThat(customerStore.updateCall)
        .as("o caso de uso grava exatamente os campos da edição, já normalizados")
        .isEqualTo(
            new Update(
                CUSTOMER_ID,
                "Ana Souza Lima",
                OTHER_CPF,
                "21987654321",
                "ana.lima@exemplo.com",
                "mudou de endereço"));
    assertThat(customerStore.taxIdChecked)
        .as("CPF novo é checado contra os clientes vivos")
        .isTrue();
    assertThat(updated)
        .satisfies(
            customer -> {
              assertThat(customer.name()).isEqualTo("Ana Souza Lima");
              assertThat(customer.taxId()).isEqualTo(OTHER_CPF);
              assertThat(customer.phone()).isEqualTo("21987654321");
              assertThat(customer.email()).isEqualTo("ana.lima@exemplo.com");
              assertThat(customer.notes()).isEqualTo("mudou de endereço");
              assertThat(customer.version()).as("lock otimista avançou").isEqualTo(1);
            });

    Recorded event = auditRecorder.only();
    assertThat(event.action()).isEqualTo("CUSTOMER_UPDATED");
    assertThat(event.entityType()).isEqualTo("CUSTOMER");
    assertThat(event.entityId()).isEqualTo(CUSTOMER_ID);
    assertThat(event.reason()).isNull();
    assertThat(snapshot(event.details(), "before"))
        .containsEntry("name", "Ana Souza")
        .containsEntry("taxId", CURRENT_CPF)
        .containsEntry("phone", "11912345678")
        .containsEntry("email", "ana@exemplo.com")
        .containsEntry("notes", "vizinho");
    assertThat(snapshot(event.details(), "after"))
        .containsEntry("name", "Ana Souza Lima")
        .containsEntry("taxId", OTHER_CPF)
        .containsEntry("phone", "21987654321")
        .containsEntry("email", "ana.lima@exemplo.com")
        .containsEntry("notes", "mudou de endereço");
  }

  @Test
  @DisplayName(
      "CPF do próprio cliente não conflita: a máscara igual não inventa edição nem conflito")
  void keepsOwnTaxIdWithoutConflictCheck() {
    CustomerSummary updated =
        useCase.execute(
            command(
                "Ana Souza Lima", "111.444.777-35", "11912345678", "ana@exemplo.com", "vizinho"));

    assertThat(customerStore.taxIdChecked)
        .as("CPF igual ao atual não tem duplicidade a checar")
        .isFalse();
    assertThat(updated.name()).isEqualTo("Ana Souza Lima");
    assertThat(auditRecorder.recorded).hasSize(1);
  }

  @Test
  @DisplayName("edição sem mudança efetiva é no-op: devolve como está, sem gravar nem auditar")
  void keepsCustomerWhenNothingChanged() {
    // A máscara e os espaços são normalizados: o que se compara é o que iria para o banco.
    CustomerSummary unchanged =
        useCase.execute(
            command(
                "Ana Souza", "111.444.777-35", "(11) 91234-5678", "ana@exemplo.com", "vizinho"));

    assertThat(unchanged.version()).isZero();
    assertThat(customerStore.updateCall).as("o no-op não toca na linha").isNull();
    assertThat(auditRecorder.recorded).as("o no-op não inventa evento").isEmpty();
  }

  @Test
  @DisplayName("CPF informado e inválido é 400 sem gravar")
  void rejectsInvalidTaxId() {
    assertThatThrownBy(() -> useCase.execute(command("Ana Souza", "11144477736", null, null, null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));

    assertThat(customerStore.updateCall).isNull();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("CPF novo já tomado por outro cliente vivo é 409 sem gravar")
  void rejectsTaxIdTakenByAnotherCustomer() {
    customerStore.activeTaxIds.add(OTHER_CPF);

    assertThatThrownBy(() -> useCase.execute(command("Ana Souza", OTHER_CPF, null, null, null)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.TAX_ID_ALREADY_EXISTS));

    assertThat(customerStore.updateCall).isNull();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("cliente inexistente ou desativado conta como inexistente: 404 sem gravar")
  void rejectsCustomerOutOfCatalog() {
    for (CustomerSummary out : List.of(withActive(false), withDeletedAt(Instant.now()))) {
      customerStore.stored = out;

      assertThatThrownBy(() -> useCase.execute(command("Ana Souza", null, null, null, null)))
          .isInstanceOfSatisfying(
              NotFoundException.class,
              error -> assertThat(error.code()).isEqualTo(ErrorCode.CUSTOMER_NOT_FOUND));

      assertThat(customerStore.updateCall).isNull();
    }
    assertThat(auditRecorder.recorded).isEmpty();
  }

  private static UpdateCustomerCommand command(
      String name, String taxId, String phone, String email, String notes) {
    return new UpdateCustomerCommand(CUSTOMER_ID, name, taxId, phone, email, notes);
  }

  /** Antes/depois aninhado em {@code details}, como o caso de uso o entregou. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> snapshot(Map<String, Object> details, String key) {
    return (Map<String, Object>) details.get(key);
  }

  private static CustomerSummary withActive(boolean active) {
    return new CustomerSummary(
        CUSTOMER_ID,
        STORE_ID,
        "Ana Souza",
        CURRENT_CPF,
        "11912345678",
        "ana@exemplo.com",
        "vizinho",
        active,
        CREATED_AT,
        CREATED_AT,
        null,
        0);
  }

  private static CustomerSummary withDeletedAt(Instant deletedAt) {
    return new CustomerSummary(
        CUSTOMER_ID,
        STORE_ID,
        "Ana Souza",
        CURRENT_CPF,
        "11912345678",
        "ana@exemplo.com",
        "vizinho",
        false,
        CREATED_AT,
        CREATED_AT,
        deletedAt,
        0);
  }

  /**
   * Dublê de {@link CustomerStore}: guarda um cliente só — o "banco" que o caso de uso lê e
   * reescreve — a última chamada de {@code update} e os CPFs já em uso.
   */
  private static final class FakeCustomerStore implements CustomerStore {

    private final Set<String> activeTaxIds = new HashSet<>();
    private CustomerSummary stored = withActive(true);
    private Update updateCall;
    private boolean taxIdChecked;

    @Override
    public Optional<CustomerSummary> findById(UUID id) {
      return Optional.ofNullable(stored)
          .filter(customer -> customer.deletedAt() == null && customer.active())
          .filter(customer -> customer.id().equals(id));
    }

    @Override
    public Optional<CustomerSummary> update(
        UUID id, String name, String taxId, String phone, String email, String notes) {
      updateCall = new Update(id, name, taxId, phone, email, notes);
      if (stored == null || !stored.id().equals(id)) {
        return Optional.empty();
      }
      stored =
          new CustomerSummary(
              stored.id(),
              stored.storeId(),
              name,
              taxId,
              phone,
              email,
              notes,
              stored.active(),
              stored.createdAt(),
              stored.updatedAt(),
              stored.deletedAt(),
              stored.version() + 1);
      return Optional.of(stored);
    }

    @Override
    public boolean existsActiveTaxId(String taxId) {
      taxIdChecked = true;
      return activeTaxIds.contains(taxId);
    }

    @Override
    public UUID insert(NewCustomer customer) {
      throw new UnsupportedOperationException("insert não é usado por UpdateCustomer");
    }

    @Override
    public List<CustomerSummary> search(String search, int page, int size) {
      throw new UnsupportedOperationException("search não é usado por UpdateCustomer");
    }

    @Override
    public long count(String search) {
      throw new UnsupportedOperationException("count não é usado por UpdateCustomer");
    }

    @Override
    public Optional<CustomerSummary> disable(UUID id) {
      throw new UnsupportedOperationException("disable não é usado por UpdateCustomer");
    }
  }

  /**
   * Dublê de {@link AuditRecorder}: guarda o que o caso de uso pediu para gravar, sem CDI e sem
   * banco. A subclasse só sobrescreve {@code record} — o caminho de verdade (contexto + INSERT) é
   * do passo 303 e tem teste próprio contra PostgreSQL.
   */
  private static final class FakeAuditRecorder extends AuditRecorder {

    private final List<Recorded> recorded = new ArrayList<>();

    @Override
    public void record(
        String action,
        String entityType,
        UUID entityId,
        String reason,
        Map<String, Object> details) {
      recorded.add(new Recorded(action, entityType, entityId, reason, details));
    }

    /** Único evento do cenário; o teste falha se o caso de uso gravou zero ou dois. */
    private Recorded only() {
      assertThat(recorded).as("eventos de auditoria do cenário").hasSize(1);
      return recorded.getFirst();
    }
  }

  /** Evento como o caso de uso o entregou ao gravador. */
  private record Recorded(
      String action,
      String entityType,
      UUID entityId,
      String reason,
      Map<String, Object> details) {}

  /** Chamada de update que o caso de uso fez à porta. */
  private record Update(
      UUID id, String name, String taxId, String phone, String email, String notes) {}
}
