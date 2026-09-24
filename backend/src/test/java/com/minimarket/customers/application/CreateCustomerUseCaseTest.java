package com.minimarket.customers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.Store;
import java.math.BigDecimal;
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
 * Unitários puros do {@link CreateCustomerUseCase}, sem Quarkus e sem banco: as portas são dublês
 * escritos à mão. O gravador de auditoria também é dublê — o evento é conferido como o caso de uso
 * o entregou, e a gravação de verdade contra o PostgreSQL fica com o teste de API do 502b.
 */
class CreateCustomerUseCaseTest {

  private static final String STORE_CODE = "MATRIZ";
  private static final String VALID_CPF = "11144477735";
  private static final String VALID_MASKED_CPF = "111.444.777-35";
  private static final String MASKED_PHONE = "(11) 91234-5678";
  private static final String DIGITS_PHONE = "11912345678";

  private final FakeCustomerStore customerStore = new FakeCustomerStore();
  private final FakeStoreLookup storeLookup = new FakeStoreLookup();
  private final FakeAuditRecorder auditRecorder = new FakeAuditRecorder();

  private CreateCustomerUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new CreateCustomerUseCase();
    useCase.customerStore = customerStore;
    useCase.storeLookup = storeLookup;
    useCase.auditRecorder = auditRecorder;
    useCase.defaultStoreCode = STORE_CODE;
  }

  @Test
  @DisplayName(
      "cria cliente com CPF e telefone normalizados, loja resolvida e evento CUSTOMER_CREATED")
  void createsCustomer() {
    CustomerSummary created = useCase.execute(command("Ana Souza", VALID_MASKED_CPF, MASKED_PHONE));

    assertThat(customerStore.inserted.storeId())
        .as("loja vem da configuração, não do comando")
        .isEqualTo(storeLookup.storeId);
    assertThat(customerStore.inserted.name()).isEqualTo("Ana Souza");
    assertThat(customerStore.inserted.taxId()).as("CPF só com dígitos").isEqualTo(VALID_CPF);
    assertThat(customerStore.inserted.phone())
        .as("telefone só com dígitos: é assim que a busca o acha")
        .isEqualTo(DIGITS_PHONE);
    assertThat(customerStore.inserted.email()).isEqualTo("ana@exemplo.com");
    assertThat(customerStore.inserted.notes()).isEqualTo("vizinho");
    assertThat(customerStore.taxIdChecked)
        .as("CPF válido ainda passa pela checagem de duplicidade");

    assertThat(created)
        .as("a resposta relê o cliente gravado, com os defaults que o banco completou")
        .satisfies(
            customer -> {
              assertThat(customer.id()).isEqualTo(customerStore.generatedId);
              assertThat(customer.active()).isTrue();
              assertThat(customer.deletedAt()).isNull();
              assertThat(customer.version()).isZero();
              assertThat(customer.createdAt()).isEqualTo(FakeCustomerStore.CREATED_AT);
            });

    Recorded event = auditRecorder.only();
    assertThat(event.action()).isEqualTo("CUSTOMER_CREATED");
    assertThat(event.entityType()).isEqualTo("CUSTOMER");
    assertThat(event.entityId()).isEqualTo(customerStore.generatedId);
    assertThat(event.reason()).isNull();
    assertThat(event.details())
        .containsEntry("name", "Ana Souza")
        .containsEntry("taxId", VALID_CPF);
  }

  @Test
  @DisplayName(
      "CPF e telefone em branco viram nulo, sem checar duplicidade nem inventar evento nulo")
  void normalizesBlankTaxIdAndPhoneToNull() {
    CustomerSummary created = useCase.execute(command("Ana Souza", "   ", "()  -  "));

    assertThat(customerStore.inserted.taxId()).as("cliente sem CPF é permitido").isNull();
    assertThat(customerStore.inserted.phone()).as("sem dígito nenhum é sem telefone").isNull();
    assertThat(customerStore.taxIdChecked).as("sem CPF não há duplicidade a checar").isFalse();
    assertThat(created.taxId()).isNull();
    assertThat(auditRecorder.only().details()).containsEntry("taxId", null);
  }

  @Test
  @DisplayName("CPF informado com dígito verificador errado é 400 sem inserir")
  void rejectsInvalidTaxId() {
    for (String invalid : List.of("11144477736", "11111111111", "123")) {
      assertThatThrownBy(() -> useCase.execute(command("Ana Souza", invalid, null)))
          .isInstanceOfSatisfying(
              BusinessException.class,
              error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    assertThat(customerStore.inserted).isNull();
    assertThat(customerStore.taxIdChecked).as("CPF inválido barra antes da checagem").isFalse();
    assertThat(auditRecorder.recorded).as("cadastro recusado não inventa evento").isEmpty();
  }

  @Test
  @DisplayName("CPF duplicado entre clientes vivos é 409 sem inserir")
  void rejectsDuplicateTaxId() {
    customerStore.activeTaxIds.add(VALID_CPF);

    assertThatThrownBy(() -> useCase.execute(command("Ana Clara", VALID_MASKED_CPF, null)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.TAX_ID_ALREADY_EXISTS));

    assertThat(customerStore.inserted).isNull();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  private static CreateCustomerCommand command(String name, String taxId, String phone) {
    return new CreateCustomerCommand(name, taxId, phone, "ana@exemplo.com", "vizinho");
  }

  /** Dublê de {@link CustomerStore}: guarda a última inserção e simula CPFs já em uso. */
  private static final class FakeCustomerStore implements CustomerStore {

    /** Instante fixo do "banco" do dublê: o resultado da criação é conferido campo a campo. */
    private static final Instant CREATED_AT = Instant.parse("2026-01-02T03:04:05Z");

    private final Set<String> activeTaxIds = new HashSet<>();
    private final UUID generatedId = UUID.randomUUID();
    private NewCustomer inserted;
    private boolean taxIdChecked;

    @Override
    public UUID insert(NewCustomer customer) {
      inserted = customer;
      if (customer.taxId() != null) {
        activeTaxIds.add(customer.taxId());
      }
      return generatedId;
    }

    @Override
    public boolean existsActiveTaxId(String taxId) {
      taxIdChecked = true;
      return activeTaxIds.contains(taxId);
    }

    /**
     * O que o adaptador JPA devolveria na releitura pós-insert: o cliente gravado com os defaults
     * que o banco completou — ativo, sem deletedAt, {@code version} 0 e timestamps.
     */
    @Override
    public Optional<CustomerSummary> findById(UUID id) {
      if (inserted == null || !generatedId.equals(id)) {
        return Optional.empty();
      }
      return Optional.of(
          new CustomerSummary(
              generatedId,
              inserted.storeId(),
              inserted.name(),
              inserted.taxId(),
              inserted.phone(),
              inserted.email(),
              inserted.notes(),
              true,
              CREATED_AT,
              CREATED_AT,
              null,
              0L));
    }

    @Override
    public List<CustomerSummary> search(String search, int page, int size) {
      throw new UnsupportedOperationException("search não é usado por CreateCustomer");
    }

    @Override
    public Optional<CustomerSummary> findAnyById(UUID id) {
      throw new UnsupportedOperationException("findAnyById não é usado por CreateCustomer");
    }

    @Override
    public long count(String search) {
      throw new UnsupportedOperationException("count não é usado por CreateCustomer");
    }

    @Override
    public Optional<CustomerSummary> update(
        UUID id, String name, String taxId, String phone, String email, String notes) {
      throw new UnsupportedOperationException("update não é usado por CreateCustomer");
    }

    @Override
    public Optional<CustomerSummary> disable(UUID id) {
      throw new UnsupportedOperationException("disable não é usado por CreateCustomer");
    }
  }

  /** Dublê de {@link StoreLookup}: devolve a loja configurada, como o seed da V1. */
  private static final class FakeStoreLookup implements StoreLookup {

    private final UUID storeId = UUID.randomUUID();

    @Override
    public Optional<Store> findByCode(String code) {
      return STORE_CODE.equals(code)
          ? Optional.of(new Store(storeId, STORE_CODE, "Matriz", false, new BigDecimal("10.00")))
          : Optional.empty();
    }

    @Override
    public Optional<Store> findById(UUID id) {
      throw new UnsupportedOperationException("findById não é usado por CreateCustomer");
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
}
