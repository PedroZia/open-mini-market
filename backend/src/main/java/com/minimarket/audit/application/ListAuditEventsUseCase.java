package com.minimarket.audit.application;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;

/**
 * Consulta do log de auditoria com filtros e paginação (passo 1001, §7.3 e §9.3). O caso de uso
 * valida os parâmetros, aplica o teto de {@code size} e resolve o {@code sort} — string do cliente
 * nunca chega ao JPQL. Leitura pura, sem {@code @Transactional}: não grava nada — mesma escolha do
 * {@code ListSalesUseCase} e do {@code ListProductsUseCase}.
 *
 * <p>A visibilidade não é filtrada aqui: a rota exige {@code audit.read} (§4.5), então quem chega
 * ao caso de uso enxerga o log inteiro — o OPERADOR não tem a permissão e recebe 403 no porteiro da
 * rota.
 */
@ApplicationScoped
public class ListAuditEventsUseCase {

  /** Teto de {@code size} do §9.1: valores maiores são limitados, não recusados. */
  private static final int MAX_SIZE = 100;

  /** Único campo ordenável do log: os índices de §5.3 são todos por {@code occurred_at}. */
  private static final String OCCURRED_AT = "occurredat";

  @Inject AuditEventQueryStore auditEventQueryStore;

  /**
   * {@code size} acima do teto é limitado a {@link #MAX_SIZE}; {@code page} negativo, {@code size}
   * menor que 1 ou {@code sort} fora da whitelist → 400 {@code VALIDATION_ERROR}. A página sai com
   * o total de eventos e o total de páginas calculado sobre o {@code size} efetivo.
   */
  public AuditEventPage execute(AuditEventFilter filter, String sort, int page, int size) {
    requireValidPage(page);
    requireValidSize(size);
    int limitedSize = Math.min(size, MAX_SIZE);
    boolean ascending = parseAscending(sort);

    List<AuditEventSummary> items =
        auditEventQueryStore.search(filter, ascending, page, limitedSize);
    long totalItems = auditEventQueryStore.count(filter);
    int totalPages = (int) ((totalItems + limitedSize - 1) / limitedSize);
    return new AuditEventPage(items, page, limitedSize, totalItems, totalPages);
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
   * Aceita {@code occurredat} em qualquer caixa ou {@code occurredat,asc|desc}; o default é {@code
   * occurredat,desc} — a investigação quer o mais recente primeiro (aceite do passo). Campo fora da
   * whitelist ou direção desconhecida → 400.
   */
  private static boolean parseAscending(String sort) {
    if (sort == null || sort.isBlank()) {
      return false;
    }
    String[] parts = sort.split(",", -1);
    if (parts.length > 2 || !OCCURRED_AT.equals(parts[0].trim().toLowerCase(Locale.ROOT))) {
      throw invalidSort(sort);
    }
    if (parts.length == 1) {
      return false;
    }
    String direction = parts[1].trim().toLowerCase(Locale.ROOT);
    if (!direction.equals("asc") && !direction.equals("desc")) {
      throw invalidSort(sort);
    }
    return direction.equals("asc");
  }

  private static BusinessException invalidSort(String sort) {
    return new BusinessException(
        ErrorCode.VALIDATION_ERROR, "sort inválido: %s (use occurredAt,asc|desc)".formatted(sort));
  }
}
