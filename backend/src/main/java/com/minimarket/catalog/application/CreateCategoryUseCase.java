package com.minimarket.catalog.application;

import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Cria categoria (passo 402b). Uma execução = uma transação (§2.2, regra 6): a checagem de nome e o
 * insert vivem juntos, e o adaptador ainda traduz o 23505 do índice único como backstop caso outra
 * requisição grave o mesmo nome no meio do caminho.
 *
 * <p>A loja não vem do cliente: é a configurada ({@code minimarket.store.default-code}), como no
 * {@code GetMetaUseCase} — o MVP tem loja única (§5.4). Categoria nova nasce ativa (default da
 * tabela) e sem hierarquia obrigatória.
 */
@ApplicationScoped
public class CreateCategoryUseCase {

  @Inject CategoryStore categoryStore;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  /**
   * 404 {@code CATEGORY_NOT_FOUND} quando o pai informado não existe; 409 {@code
   * CATEGORY_NAME_ALREADY_EXISTS} quando o nome já está em uso (inclusive por categoria desativada:
   * o índice único do banco não filtra por {@code active}).
   */
  @Transactional
  public CategorySummary execute(String name, UUID parentId, int sortOrder) {
    UUID storeId = currentStoreId();
    requireExistingParent(parentId);
    if (categoryStore.existsByName(name)) {
      throw nameConflict(name);
    }
    UUID id = categoryStore.insert(new NewCategory(storeId, name, parentId, sortOrder));
    return new CategorySummary(id, storeId, name, parentId, true, sortOrder);
  }

  /** Categoria só existe dentro de uma loja; a loja atual vem da configuração, não do corpo. */
  private UUID currentStoreId() {
    return storeLookup
        .findByCode(defaultStoreCode)
        .orElseThrow(
            () -> new IllegalStateException("loja configurada não existe: " + defaultStoreCode))
        .id();
  }

  private void requireExistingParent(UUID parentId) {
    if (parentId != null && categoryStore.findById(parentId).isEmpty()) {
      throw notFound(parentId);
    }
  }

  private static ConflictException nameConflict(String name) {
    return new ConflictException(
        ErrorCode.CATEGORY_NAME_ALREADY_EXISTS, "nome %s já está em uso".formatted(name));
  }

  private static NotFoundException notFound(UUID id) {
    return new NotFoundException(
        ErrorCode.CATEGORY_NOT_FOUND, "categoria %s não encontrada".formatted(id));
  }
}
