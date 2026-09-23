package com.minimarket.users.application;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lista usuários com paginação e filtros (§9.1 e §9.3 do plano). O caso de uso valida os
 * parâmetros, aplica o teto de {@code size} e traduz o {@code sort} para a whitelist {@link
 * UserSort} — string do cliente nunca chega ao JPQL. Uma execução = uma transação (§2.2, regra 6).
 */
@ApplicationScoped
public class ListUsersUseCase {

  /** Teto de {@code size} do §9.1: valores maiores são limitados, não recusados. */
  private static final int MAX_SIZE = 100;

  /** Nomes aceitos em {@code sort}, já em minúsculas para comparar sem diferenciar maiúsculas. */
  private static final Map<String, UserSort> SORT_FIELDS =
      Map.of(
          "username", UserSort.USERNAME,
          "displayname", UserSort.DISPLAY_NAME,
          "createdat", UserSort.CREATED_AT);

  @Inject UserStore userStore;

  /**
   * {@code search} em branco = sem filtro textual; {@code active} nulo = todos os status; {@code
   * size} acima do teto é limitado a {@link #MAX_SIZE}; {@code page} negativo, {@code size} menor
   * que 1 ou {@code sort} fora da whitelist → 400.
   */
  public UserPage execute(String search, Boolean active, String sort, int page, int size) {
    requireValidPage(page);
    requireValidSize(size);
    int limitedSize = Math.min(size, MAX_SIZE);
    Sort ordering = parseSort(sort);

    List<UserSummary> items =
        userStore.search(search, active, ordering.field(), ordering.ascending(), page, limitedSize);
    long totalItems = userStore.count(search, active);
    int totalPages = (int) ((totalItems + limitedSize - 1) / limitedSize);
    return new UserPage(items, page, limitedSize, totalItems, totalPages);
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

  /**
   * Aceita {@code campo} ou {@code campo,asc|desc} (default {@code username,asc}); campo fora da
   * whitelist ou direção desconhecida → 400.
   */
  private static Sort parseSort(String sort) {
    if (sort == null || sort.isBlank()) {
      return new Sort(UserSort.USERNAME, true);
    }
    String[] parts = sort.split(",", -1);
    UserSort field =
        parts.length <= 2 ? SORT_FIELDS.get(parts[0].trim().toLowerCase(Locale.ROOT)) : null;
    if (field == null) {
      throw invalidSort(sort);
    }
    if (parts.length == 1) {
      return new Sort(field, true);
    }
    String direction = parts[1].trim().toLowerCase(Locale.ROOT);
    if (!direction.equals("asc") && !direction.equals("desc")) {
      throw invalidSort(sort);
    }
    return new Sort(field, direction.equals("asc"));
  }

  private static BusinessException invalidSort(String sort) {
    return new BusinessException(
        ErrorCode.VALIDATION_ERROR, "sort inválido: %s (use campo,asc|desc)".formatted(sort));
  }

  /** Campo ordenável já resolvido na whitelist, com a direção pedida. */
  private record Sort(UserSort field, boolean ascending) {}
}
