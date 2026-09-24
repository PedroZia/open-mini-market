package com.minimarket.customers.application;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Lista clientes com busca e paginação (§9.1 e §9.3 do plano). O caso de uso valida os parâmetros e
 * aplica o teto de {@code size}; o filtro textual (nome por trecho, CPF e telefone exatos em
 * dígitos) e a ordenação por nome são da porta {@link CustomerStore}. Leitura pura, sem
 * {@code @Transactional}: não grava nada — mesma escolha do {@code ListProductsUseCase} e do {@code
 * ListUsersUseCase}.
 *
 * <p>O §9.3 não define {@code sort} para clientes: a ordenação é fixa por nome, sem whitelist de
 * campo.
 */
@ApplicationScoped
public class ListCustomersUseCase {

  /** Teto de {@code size} do §9.1: valores maiores são limitados, não recusados. */
  private static final int MAX_SIZE = 100;

  @Inject CustomerStore customerStore;

  /**
   * {@code search} em branco = sem filtro textual; {@code size} acima do teto é limitado a {@link
   * #MAX_SIZE}; {@code page} negativo ou {@code size} menor que 1 → 400 {@code VALIDATION_ERROR}.
   */
  public CustomerPage execute(String search, int page, int size) {
    requireValidPage(page);
    requireValidSize(size);
    int limitedSize = Math.min(size, MAX_SIZE);

    List<CustomerSummary> items = customerStore.search(search, page, limitedSize);
    long totalItems = customerStore.count(search);
    int totalPages = (int) ((totalItems + limitedSize - 1) / limitedSize);
    return new CustomerPage(items, page, limitedSize, totalItems, totalPages);
  }

  private static void requireValidPage(int page) {
    if (page < 0) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "page deve ser maior ou igual a 0");
    }
  }

  private static void requireValidSize(int size) {
    if (size < 1) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "size deve ser maior ou igual a 1");
    }
  }
}
