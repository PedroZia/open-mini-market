package com.minimarket.sales.infrastructure;

import com.github.f4b6a3.uuid.UuidCreator;
import com.minimarket.sales.application.SaleSummary;
import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleItem;
import com.minimarket.sales.domain.SaleStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Conversão entre o agregado {@link Sale} e as entidades JPA de {@code sales}/{@code sale_items}:
 * nada de JPA fora daqui. A rehidratação usa só os métodos públicos do domínio (construtor, {@code
 * addItem}, {@code applyDiscount}, {@code linkCustomer}, {@code complete}), como o §4.1 manda — a
 * linha não reconstrói estado que o agregado não aceita.
 */
final class SaleMapper {

  private SaleMapper() {}

  /** Venda nova com o cabeçalho do agregado; o id é o do agregado (UUIDv7 do caso de uso). */
  static SaleEntity toEntity(Sale sale) {
    SaleEntity entity =
        new SaleEntity(
            sale.id(),
            sale.storeId(),
            sale.number(),
            sale.cashSessionId(),
            sale.cashRegisterId(),
            sale.operatorUserId(),
            sale.notes(),
            sale.createdAt());
    entity.syncFrom(sale);
    return entity;
  }

  /** Itens da venda na ordem da lista, com {@code line_number} = posição (1..n) e id novo. */
  static List<SaleItemEntity> toItemEntities(Sale sale) {
    List<SaleItem> items = sale.items();
    List<SaleItemEntity> entities = new ArrayList<>(items.size());
    for (int index = 0; index < items.size(); index++) {
      entities.add(toItemEntity(sale.id(), index + 1, items.get(index)));
    }
    return entities;
  }

  /** Item novo: o id (UUIDv7) é gerado aqui, como nos outros repositórios (§5.1). */
  static SaleItemEntity toItemEntity(UUID saleId, int lineNumber, SaleItem item) {
    return new SaleItemEntity(
        UuidCreator.getTimeOrderedEpoch(),
        saleId,
        lineNumber,
        item.productId(),
        item.barcode(),
        item.name(),
        item.unit(),
        item.unitPrice(),
        item.quantity(),
        item.lineTotal());
  }

  /**
   * Agregado a partir da linha e dos itens na ordem de {@code line_number}. A ordem da rehidratação
   * é a ordem em que o agregado aceita as transições: itens, desconto e cliente antes do estado
   * final — a venda concluída ou cancelada é imutável e não aceitaria mais nenhum deles. Não
   * reconstrói o que o domínio não representa e nunca silencia: a linha com dois itens do mesmo
   * produto (o agregado guarda um item por produto) e o que o próprio domínio recusa (conclusão sem
   * instante, desconto sem valor, cancelamento sem motivo/autor/instante) falham explícito.
   */
  static Sale toDomain(SaleEntity entity, List<SaleItemEntity> itemEntities) {
    Sale sale =
        new Sale(
            entity.getId(),
            entity.getStoreId(),
            entity.getNumber(),
            entity.getCashSessionId(),
            entity.getCashRegisterId(),
            entity.getOperatorUserId(),
            entity.getNotes(),
            entity.getCreatedAt());
    for (SaleItemEntity item : itemEntities) {
      sale.addItem(
          item.getProductId(),
          item.getBarcode(),
          item.getName(),
          item.getUnit(),
          item.getUnitPrice(),
          item.getQuantity());
    }
    if (sale.itemCount() != itemEntities.size()) {
      throw new IllegalStateException(
          "venda %s tem mais de um item do mesmo produto: o agregado guarda um item por produto"
              .formatted(entity.getId()));
    }
    if (entity.getDiscountType() != null) {
      sale.applyDiscount(
          entity.getDiscountType(), entity.getDiscountValue(), entity.getDiscountReason());
    }
    // O cliente entra antes da conclusão: a venda concluída é imutável e não aceitaria o vínculo.
    if (entity.getCustomerId() != null) {
      sale.linkCustomer(entity.getCustomerId());
    }
    // O estado final entra por último: a venda concluída ou cancelada não aceita mais nada.
    if (entity.getStatus() == SaleStatus.COMPLETED) {
      sale.complete(entity.getCompletedAt());
    } else if (entity.getStatus() == SaleStatus.CANCELLED) {
      sale.cancel(entity.getCancelReason(), entity.getCancelledByUserId(), entity.getCancelledAt());
    }
    return sale;
  }

  /** Projeção do cabeçalho para a porta: nada de entidade JPA na saída. */
  static SaleSummary toSummary(SaleEntity entity) {
    return new SaleSummary(
        entity.getId(),
        entity.getStoreId(),
        entity.getNumber(),
        entity.getStatus(),
        entity.getCashSessionId(),
        entity.getCashRegisterId(),
        entity.getOperatorUserId(),
        entity.getCustomerId(),
        entity.getSubtotal(),
        entity.getDiscountAmount(),
        entity.getTotal(),
        entity.getPaidAmount(),
        entity.getChangeAmount(),
        entity.getItemCount(),
        entity.getCreatedAt(),
        entity.getCompletedAt());
  }
}
