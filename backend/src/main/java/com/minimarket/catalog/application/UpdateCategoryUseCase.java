package com.minimarket.catalog.application;

import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.UUID;

/**
 * Atualiza nome, categoria pai e ordenação (passo 402b); o estado ativo só muda no {@code DELETE}
 * ({@link DeactivateCategoryUseCase}). Uma execução = uma transação (§2.2, regra 6): a checagem de
 * nome e a gravação vivem juntas, com o 23505 do índice único traduzido pelo adaptador como
 * backstop.
 */
@ApplicationScoped
public class UpdateCategoryUseCase {

  @Inject CategoryStore categoryStore;

  /**
   * 404 {@code CATEGORY_NOT_FOUND} quando a categoria (ou o pai informado) não existe; 409 {@code
   * CATEGORY_NAME_ALREADY_EXISTS} quando o nome já é de outra categoria. Categoria desativada
   * continua podendo ser editada — desativar não apaga a linha.
   */
  @Transactional
  public CategorySummary execute(UUID id, String name, UUID parentId, int sortOrder) {
    requireExisting(id);
    requireExistingParent(parentId);
    if (categoryStore.existsByNameExceptId(name, id)) {
      throw nameConflict(name);
    }
    categoryStore.update(id, name, parentId, sortOrder);
    return categoryStore.findById(id).orElseThrow(() -> notFound(id));
  }

  private void requireExisting(UUID id) {
    if (categoryStore.findById(id).isEmpty()) {
      throw notFound(id);
    }
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
