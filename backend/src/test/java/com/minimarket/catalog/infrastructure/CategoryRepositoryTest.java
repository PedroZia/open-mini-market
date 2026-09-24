package com.minimarket.catalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.IntegrationTestBase;
import com.minimarket.catalog.application.CategorySummary;
import com.minimarket.catalog.application.NewCategory;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integração do {@link CategoryRepository} contra PostgreSQL real (Dev Services). Cada teste roda
 * em transação revertida ao final ({@code @TestTransaction}).
 *
 * <p>O id da loja MATRIZ vem da porta {@link StoreLookup}, com o código configurado: o teste não
 * importa infrastructure de outro módulo.
 */
@QuarkusTest
class CategoryRepositoryTest extends IntegrationTestBase {

  @Inject CategoryRepository categoryRepository;

  @Inject StoreLookup storeLookup;

  @Inject EntityManager entityManager;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  private UUID storeId;

  @Test
  @TestTransaction
  @DisplayName("insere com id UUIDv7 e timestamps e devolve a categoria pelo id")
  void insertsAndFindsById() {
    UUID parentId = categoryRepository.insert(new NewCategory(storeId(), "Bebidas", null, 1));
    UUID id = categoryRepository.insert(new NewCategory(storeId(), "Sucos", parentId, 2));
    entityManager.flush();
    entityManager.clear();

    assertThat(id.version()).as("UUIDv7").isEqualTo(7);
    assertThat(categoryRepository.findById(id))
        .hasValueSatisfying(
            found -> {
              assertThat(found.id()).isEqualTo(id);
              assertThat(found.storeId()).isEqualTo(storeId());
              assertThat(found.name()).isEqualTo("Sucos");
              assertThat(found.parentId()).isEqualTo(parentId);
              assertThat(found.active()).isTrue();
              assertThat(found.sortOrder()).isEqualTo(2);
            });

    CategoryEntity raw = entityManager.find(CategoryEntity.class, id);
    assertThat(raw.getCreatedAt()).isNotNull();
    assertThat(raw.getUpdatedAt()).isNotNull();
    assertThat(raw.getVersion()).isZero();

    assertThat(categoryRepository.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  @TestTransaction
  @DisplayName("findAll devolve ativas e desativadas ordenadas por sort_order e nome")
  void findsAllOrderedBySortOrderThenName() {
    UUID bebidas = categoryRepository.insert(new NewCategory(storeId(), "Bebidas", null, 1));
    UUID zebrado = categoryRepository.insert(new NewCategory(storeId(), "Zebrado", null, 1));
    UUID desativada = categoryRepository.insert(new NewCategory(storeId(), "Limpeza", null, 9));
    categoryRepository.deactivate(desativada);
    entityManager.flush();
    entityManager.clear();

    List<CategorySummary> all = categoryRepository.findAll();
    assertThat(all).extracting(CategorySummary::id).containsExactly(bebidas, zebrado, desativada);
    assertThat(all)
        .extracting(CategorySummary::name)
        .containsExactly("Bebidas", "Zebrado", "Limpeza");
    assertThat(all).extracting(CategorySummary::active).containsExactly(true, true, false);
  }

  @Test
  @TestTransaction
  @DisplayName("update grava nome, pai e ordenação e avança updated_at")
  void updates() {
    UUID parent = categoryRepository.insert(new NewCategory(storeId(), "Bebidas", null, 1));
    UUID id = categoryRepository.insert(new NewCategory(storeId(), "Sucos", null, 2));
    entityManager.flush();
    entityManager.clear();
    Instant before = entityManager.find(CategoryEntity.class, id).getUpdatedAt();

    categoryRepository.update(id, "Sucos naturais", parent, 5);
    entityManager.flush();
    entityManager.clear();

    assertThat(categoryRepository.findById(id))
        .hasValueSatisfying(
            found -> {
              assertThat(found.name()).isEqualTo("Sucos naturais");
              assertThat(found.parentId()).isEqualTo(parent);
              assertThat(found.sortOrder()).isEqualTo(5);
              assertThat(found.active()).isTrue();
            });
    assertThat(entityManager.find(CategoryEntity.class, id).getUpdatedAt())
        .isAfterOrEqualTo(before);
    // Id desconhecido é no-op, como nas outras escritas por id.
    categoryRepository.update(UUID.randomUUID(), "Fantasma", null, 0);
  }

  @Test
  @TestTransaction
  @DisplayName("deactivate marca active = false sem apagar a linha")
  void deactivates() {
    UUID id = categoryRepository.insert(new NewCategory(storeId(), "Descartáveis", null, 3));
    entityManager.flush();
    entityManager.clear();

    categoryRepository.deactivate(id);
    entityManager.flush();
    entityManager.clear();

    assertThat(categoryRepository.findById(id))
        .hasValueSatisfying(
            found -> {
              assertThat(found.active()).isFalse();
              assertThat(found.name()).isEqualTo("Descartáveis");
            });
    assertThat(categoryRepository.findAll()).extracting(CategorySummary::id).contains(id);
    // Id desconhecido é no-op, como nas outras escritas por id.
    categoryRepository.deactivate(UUID.randomUUID());
  }

  @Test
  @TestTransaction
  @DisplayName("existsByName e existsByNameExceptId enxergam categoria desativada")
  void checksExistingNames() {
    UUID id = categoryRepository.insert(new NewCategory(storeId(), "Bebidas", null, 1));
    categoryRepository.insert(new NewCategory(storeId(), "Limpeza", null, 2));
    categoryRepository.deactivate(id);
    entityManager.flush();
    entityManager.clear();

    assertThat(categoryRepository.existsByName("Bebidas")).isTrue();
    assertThat(categoryRepository.existsByName("Sucos")).isFalse();
    assertThat(categoryRepository.existsByNameExceptId("Bebidas", id)).isFalse();
    assertThat(categoryRepository.existsByNameExceptId("Bebidas", UUID.randomUUID())).isTrue();
    assertThat(categoryRepository.existsByNameExceptId("Limpeza", id)).isTrue();
  }

  @Test
  @TestTransaction
  @DisplayName("nome duplicado na mesma loja vira ConflictException no insert")
  void translatesDuplicateNameOnInsert() {
    categoryRepository.insert(new NewCategory(storeId(), "Bebidas", null, 1));
    entityManager.flush();

    assertThatThrownBy(
            () -> categoryRepository.insert(new NewCategory(storeId(), "Bebidas", null, 2)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.CATEGORY_NAME_ALREADY_EXISTS));
  }

  @Test
  @TestTransaction
  @DisplayName("nome duplicado na mesma loja vira ConflictException no update")
  void translatesDuplicateNameOnUpdate() {
    categoryRepository.insert(new NewCategory(storeId(), "Bebidas", null, 1));
    UUID id = categoryRepository.insert(new NewCategory(storeId(), "Limpeza", null, 2));
    entityManager.flush();

    assertThatThrownBy(() -> categoryRepository.update(id, "Bebidas", null, 2))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.CATEGORY_NAME_ALREADY_EXISTS));
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
