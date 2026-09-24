package com.minimarket.customers.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.IntegrationTestBase;
import com.minimarket.customers.application.CustomerSummary;
import com.minimarket.customers.application.NewCustomer;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link CustomerRepository} contra PostgreSQL real (Dev Services). Cada teste roda
 * em transação revertida ao final ({@code @TestTransaction}).
 *
 * <p>O id da loja MATRIZ vem da porta {@link StoreLookup}, com o código configurado: o teste não
 * importa infrastructure de outro módulo.
 */
@QuarkusTest
class CustomerRepositoryTest extends IntegrationTestBase {

  @Inject CustomerRepository customerRepository;

  @Inject StoreLookup storeLookup;

  @Inject EntityManager entityManager;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  private UUID storeId;

  @Test
  @TestTransaction
  @DisplayName("insere com id UUIDv7, timestamps e version zerada, e devolve o cliente pelo id")
  void insertsAndFindsById() {
    UUID id = insert("Ana Souza", "11144477735", "11912345678", "ana@exemplo.com", "vizinho");

    assertThat(id.version()).as("UUIDv7").isEqualTo(7);
    assertThat(customerRepository.findById(id))
        .hasValueSatisfying(
            found -> {
              assertThat(found.storeId()).isEqualTo(storeId());
              assertThat(found.name()).isEqualTo("Ana Souza");
              assertThat(found.taxId()).isEqualTo("11144477735");
              assertThat(found.phone()).isEqualTo("11912345678");
              assertThat(found.email()).isEqualTo("ana@exemplo.com");
              assertThat(found.notes()).isEqualTo("vizinho");
              assertThat(found.active()).isTrue();
              assertThat(found.createdAt()).isNotNull();
              assertThat(found.updatedAt()).isNotNull();
              assertThat(found.deletedAt()).isNull();
              assertThat(found.version()).isZero();
            });

    assertThat(customerRepository.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("search casa trecho do nome sem diferenciar maiúsculas e ordena por nome")
  void searchesByNameIgnoringCase() {
    insert("Bruno Lima", null, null, null, null);
    insert("ana clara", null, null, null, null);
    insert("Ana Souza", null, null, null, null);

    assertThat(names(search("ana"))).containsExactly("ana clara", "Ana Souza");
    assertThat(names(search("ANA"))).containsExactly("ana clara", "Ana Souza");
    assertThat(names(search("souza"))).containsExactly("Ana Souza");
    assertThat(names(search("  "))).containsExactly("ana clara", "Ana Souza", "Bruno Lima");
    assertThat(names(search(null))).hasSize(3);
    assertThat(names(search("zeca"))).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("search casa CPF e telefone pelo termo em dígitos, com ou sem máscara")
  void searchesByTaxIdAndPhone() {
    insert("Ana Souza", "11144477735", "11912345678", null, null);
    insert("Bruno Lima", "52998224725", "21987654321", null, null);

    assertThat(names(search("11144477735"))).containsExactly("Ana Souza");
    assertThat(names(search("111.444.777-35"))).containsExactly("Ana Souza");
    assertThat(names(search("11912345678"))).containsExactly("Ana Souza");
    assertThat(names(search("52998224725"))).containsExactly("Bruno Lima");
    assertThat(names(search("21987654321"))).containsExactly("Bruno Lima");
    assertThat(names(search("11144477736"))).as("dígito trocado não acha ninguém").isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("search pagina por page/size sem repetir item e devolve vazio além da última página")
  void paginates() {
    insert("Ana Souza", null, null, null, null);
    insert("Bruno Lima", null, null, null, null);
    insert("Carla Dias", null, null, null, null);

    List<CustomerSummary> firstPage = customerRepository.search(null, 0, 2);
    List<CustomerSummary> secondPage = customerRepository.search(null, 1, 2);

    assertThat(names(firstPage)).containsExactly("Ana Souza", "Bruno Lima");
    assertThat(names(secondPage)).containsExactly("Carla Dias");
    assertThat(secondPage)
        .extracting(CustomerSummary::id)
        .doesNotContainAnyElementsOf(firstPage.stream().map(CustomerSummary::id).toList());
    assertThat(customerRepository.search(null, 5, 2)).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("count conta com o mesmo filtro da busca, sem paginação")
  void countsWithTheSameFilterAsSearch() {
    insert("Ana Souza", "11144477735", null, null, null);
    insert("Bruno Lima", null, null, null, null);
    insert("Carla Dias", null, null, null, null);

    assertThat(customerRepository.count(null)).isEqualTo(3);
    assertThat(customerRepository.count("souza")).isEqualTo(1);
    assertThat(customerRepository.count("11144477735")).isEqualTo(1);
    assertThat(customerRepository.count("111.444.777-35")).isEqualTo(1);
    assertThat(customerRepository.count("zeca")).isZero();
    assertThat(customerRepository.count("11144477735")).isEqualTo(search("11144477735").size());
  }

  @Test
  @TestTransaction
  @DisplayName("update grava os campos da edição e avança version e updated_at")
  void updatesDetails() {
    UUID id = insert("Ana Souza", "11144477735", "11912345678", "ana@exemplo.com", "vizinho");
    Instant before = customerRepository.findById(id).orElseThrow().updatedAt();

    Optional<CustomerSummary> updated =
        customerRepository.update(
            id, "Ana Souza Lima", "52998224725", "21987654321", "ana.lima@exemplo.com", null);

    assertThat(updated)
        .hasValueSatisfying(
            found -> {
              assertThat(found.name()).isEqualTo("Ana Souza Lima");
              assertThat(found.taxId()).isEqualTo("52998224725");
              assertThat(found.phone()).isEqualTo("21987654321");
              assertThat(found.email()).isEqualTo("ana.lima@exemplo.com");
              assertThat(found.notes()).isNull();
              assertThat(found.active()).isTrue();
              assertThat(found.version()).as("lock otimista avançou").isEqualTo(1);
            });

    entityManager.clear();
    assertThat(customerRepository.findById(id))
        .hasValueSatisfying(
            found -> {
              assertThat(found.name()).isEqualTo("Ana Souza Lima");
              assertThat(found.updatedAt()).isAfterOrEqualTo(before);
            });
    // Id desconhecido é no-op, como nas outras escritas por id.
    assertThat(customerRepository.update(UUID.randomUUID(), "Fantasma", null, null, null, null))
        .isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("disable grava active=false com deleted_at e tira o cliente da busca e do detalhe")
  void disables() {
    UUID id = insert("Ana Souza", "11144477735", null, null, null);
    insert("Bruno Lima", null, null, null, null);

    assertThat(customerRepository.disable(id))
        .hasValueSatisfying(
            found -> {
              assertThat(found.active()).isFalse();
              assertThat(found.deletedAt()).as("soft delete acompanha a desativação").isNotNull();
              assertThat(found.version()).as("lock otimista avançou").isEqualTo(1);
            });

    entityManager.clear();
    assertThat(customerRepository.findById(id)).isEmpty();
    assertThat(names(search("ana"))).isEmpty();
    assertThat(customerRepository.count(null)).isEqualTo(1);
    assertThat(customerRepository.update(id, "Ana Nova", null, null, null, null)).isEmpty();
    // Desativado de novo e id desconhecido: vazio, como nas outras escritas por id.
    assertThat(customerRepository.disable(id)).isEmpty();
    assertThat(customerRepository.disable(UUID.randomUUID())).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("findAnyById enxerga o cliente desativado, ao contrário do findById")
  void findAnyByIdSeesDisabledCustomer() {
    UUID id = insert("Ana Souza", "11144477735", null, null, null);

    assertThat(customerRepository.findAnyById(id))
        .hasValueSatisfying(
            found -> {
              assertThat(found.active()).isTrue();
              assertThat(found.deletedAt()).isNull();
            });

    customerRepository.disable(id);
    entityManager.flush();
    entityManager.clear();

    assertThat(customerRepository.findById(id))
        .as("o detalhe e a edição do 502b continuam sem ver o desativado")
        .isEmpty();
    assertThat(customerRepository.findAnyById(id))
        .as("o vínculo da venda (811) precisa distinguir desativado de inexistente")
        .hasValueSatisfying(
            found -> {
              assertThat(found.name()).isEqualTo("Ana Souza");
              assertThat(found.active()).isFalse();
              assertThat(found.deletedAt()).isNotNull();
            });
    assertThat(customerRepository.findAnyById(UUID.randomUUID())).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("existsActiveTaxId ignora cliente desativado, CPF desconhecido e nulo")
  void checksActiveTaxId() {
    UUID id = insert("Ana Souza", "11144477735", null, null, null);

    assertThat(customerRepository.existsActiveTaxId("11144477735")).isTrue();
    assertThat(customerRepository.existsActiveTaxId("52998224725")).isFalse();
    assertThat(customerRepository.existsActiveTaxId(null)).isFalse();

    customerRepository.disable(id);
    entityManager.flush();
    entityManager.clear();

    assertThat(customerRepository.existsActiveTaxId("11144477735")).isFalse();
  }

  @Test
  @TestTransaction
  @DisplayName("clientes sem CPF convivem: o índice único parcial ignora os nulos")
  void allowsMultipleCustomersWithoutTaxId() {
    UUID ana = insert("Ana Souza", null, null, null, null);
    UUID bruno = insert("Bruno Lima", null, null, null, null);

    assertThat(customerRepository.findById(ana))
        .hasValueSatisfying(found -> assertThat(found.taxId()).isNull());
    assertThat(customerRepository.findById(bruno))
        .hasValueSatisfying(found -> assertThat(found.taxId()).isNull());
  }

  @Test
  @TestTransaction
  @DisplayName("CPF duplicado entre clientes vivos vira ConflictException(TAX_ID_ALREADY_EXISTS)")
  void translatesDuplicateTaxIdOnInsert() {
    insert("Ana Souza", "11144477735", null, null, null);

    assertThatThrownBy(() -> insert("Ana Clara", "11144477735", null, null, null))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.TAX_ID_ALREADY_EXISTS));
  }

  @Test
  @TestTransaction
  @DisplayName("CPF já tomado vira ConflictException(TAX_ID_ALREADY_EXISTS) no update")
  void translatesDuplicateTaxIdOnUpdate() {
    insert("Ana Souza", "11144477735", null, null, null);
    UUID bruno = insert("Bruno Lima", "52998224725", null, null, null);

    assertThatThrownBy(
            () -> customerRepository.update(bruno, "Bruno Lima", "11144477735", null, null, null))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.TAX_ID_ALREADY_EXISTS));
  }

  @Test
  @TestTransaction
  @DisplayName("soft delete libera o CPF: o cliente novo com o mesmo documento é aceito")
  void allowsTaxIdFreedByDisable() {
    UUID antigo = insert("Ana Souza", "11144477735", null, null, null);
    customerRepository.disable(antigo);
    entityManager.flush();
    entityManager.clear();

    UUID novo = insert("Ana Souza", "11144477735", null, null, null);

    assertThat(customerRepository.findById(novo))
        .hasValueSatisfying(found -> assertThat(found.taxId()).isEqualTo("11144477735"));
    assertThat(customerRepository.existsActiveTaxId("11144477735")).isTrue();
  }

  /** Insere pelo repositório e limpa o contexto: o que o teste lê depois vem do banco. */
  private UUID insert(String name, String taxId, String phone, String email, String notes) {
    UUID id =
        customerRepository.insert(new NewCustomer(storeId(), name, taxId, phone, email, notes));
    entityManager.clear();
    return id;
  }

  private List<CustomerSummary> search(String term) {
    return customerRepository.search(term, 0, 50);
  }

  private static List<String> names(List<CustomerSummary> customers) {
    return customers.stream().map(CustomerSummary::name).toList();
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
