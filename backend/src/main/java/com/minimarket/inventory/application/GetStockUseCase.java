package com.minimarket.inventory.application;

import com.minimarket.catalog.application.ProductStore;
import com.minimarket.catalog.application.ProductSummary;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import com.minimarket.shared.domain.Store;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Detalhe do estoque de um produto (§9.3 do plano, passo 704): o saldo da loja e os últimos
 * movimentos do ledger. O produto vem da porta {@code ProductStore} do catálogo — {@code inventory
 * → catalog} é dependência de {@code application} para {@code application} permitida pelo §2.2/§4,
 * sem entidade nem infrastructure do outro módulo. Id desconhecido ou produto soft-deletado → 404
 * {@code PRODUCT_NOT_FOUND}; produto vivo sem linha de saldo responde zero com movimentos vazios.
 * Leitura pura, sem {@code @Transactional}: não grava nada.
 */
@ApplicationScoped
public class GetStockUseCase {

  /** Quantos movimentos o detalhe devolve: o histórico recente, do mais novo para o mais antigo. */
  private static final int MOVEMENTS_LIMIT = 20;

  @Inject ProductStore productStore;

  @Inject ProductStockStore productStockStore;

  @Inject StockMovementStore stockMovementStore;

  @Inject StoreLookup storeLookup;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  public StockDetail execute(UUID productId) {
    Store store = currentStore();
    ProductSummary product =
        productStore
            .findById(productId)
            .filter(found -> found.deletedAt() == null)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        ErrorCode.PRODUCT_NOT_FOUND,
                        "produto %s não encontrado".formatted(productId)));
    BigDecimal quantity =
        productStockStore
            .findByProduct(store.id(), productId)
            .map(ProductStockSummary::quantity)
            .orElse(BigDecimal.ZERO);
    StockItemSummary item =
        new StockItemSummary(
            product.id(),
            product.name(),
            product.barcode(),
            product.unit(),
            quantity,
            product.minQuantity());
    return new StockDetail(item, stockMovementStore.listByProduct(productId, MOVEMENTS_LIMIT));
  }

  /** Produto só existe dentro de uma loja; a loja atual vem da configuração, não do cliente. */
  private Store currentStore() {
    return storeLookup
        .findByCode(defaultStoreCode)
        .orElseThrow(
            () -> new IllegalStateException("loja configurada não existe: " + defaultStoreCode));
  }
}
