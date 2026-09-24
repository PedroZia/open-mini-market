package com.minimarket.catalog.application;

import com.minimarket.catalog.domain.ScaleLabel;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import com.minimarket.shared.domain.Store;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Resolução única do código lido (BR-14, passo 1104b3): o bipe do PDV ({@link
 * GetProductByBarcodeUseCase}) e o item da venda ({@code AddSaleItemUseCase}) fazem a mesma
 * pergunta — "que produto é este código e quanto ele embute?" — e as duas respostas têm de ser a
 * mesma; por isso a ordem mora aqui, uma vez só.
 *
 * <p>A ordem, do caminho quente para o mais raro:
 *
 * <ol>
 *   <li>barcode exato ({@link ProductStore#findByBarcode}): o GTIN e o código de barras comum
 *       vencem qualquer interpretação — um produto cujo barcode <em>é</em> a etiqueta resolve pelo
 *       barcode;
 *   <li>etiqueta de balança ({@link ScaleLabel#parse}) com os parâmetros da loja atual: o código
 *       interno (PLU) sai da etiqueta e a quantidade embutida de {@link ScaleLabel#quantityFor};
 *   <li>código interno digitado ({@link ProductStore#findByInternalCode}): o PLU que o operador
 *       digita ou o produto sem GTIN — o curto é completado com zeros como o cadastro o gravou
 *       ({@link InternalCodeNormalizer#forLookup}), para o mesmo produto resolver pela etiqueta e
 *       pelo código digitado;
 *   <li>404 {@code PRODUCT_NOT_FOUND}: o código não é de produto nenhum.
 * </ol>
 *
 * <p>Nada aqui filtra status nem grava: o soft-deletado já é invisível às duas portas do catálogo e
 * quem decide o que fazer com o produto inativo é o chamador. Etiqueta malformada — dentro do
 * namespace do prefixo, mas fora da config — não vira 404: o 422 {@code INVALID_INTERNAL_BARCODE}
 * sobe do parser, porque é defeito da balança, não código desconhecido.
 */
@ApplicationScoped
public class BarcodeResolver {

  private final ProductStore productStore;

  private final StoreLookup storeLookup;

  /** A loja do MVP é a configurada (§5.4); a etiqueta de balança é a que ela imprime. */
  private final String defaultStoreCode;

  @Inject
  public BarcodeResolver(
      ProductStore productStore,
      StoreLookup storeLookup,
      @ConfigProperty(name = "minimarket.store.default-code") String defaultStoreCode) {
    this.productStore = productStore;
    this.storeLookup = storeLookup;
    this.defaultStoreCode = defaultStoreCode;
  }

  /**
   * Resolve o código bruto do leitor, já com a normalização única do {@link BarcodeNormalizer}
   * (trim e sem espaços internos). Devolve o produto com a quantidade sugerida ou lança 404 {@code
   * PRODUCT_NOT_FOUND} quando nada resolve — e 422 {@code INVALID_INTERNAL_BARCODE} quando o código
   * está no namespace da etiqueta mas é malformado (do parser).
   */
  public BarcodeResolution resolve(String barcode) {
    String normalized = BarcodeNormalizer.normalize(barcode);
    if (normalized == null) {
      throw notFound(barcode);
    }
    Optional<ProductSummary> exact = productStore.findByBarcode(normalized);
    if (exact.isPresent()) {
      return new BarcodeResolution(exact.get(), null);
    }
    Store store = currentStore();
    Optional<ScaleLabel> label = ScaleLabel.parse(normalized, store);
    if (label.isPresent()) {
      ProductSummary product = internalProduct(label.get().internalCode(), normalized);
      return new BarcodeResolution(product, label.get().quantityFor(product.price()));
    }
    // O PLU digitado curto é procurado na forma canônica do cadastro (1104d): 42 e 00042 acham o
    // mesmo produto, como a etiqueta 2 + 00042 + valor.
    String typedCode = InternalCodeNormalizer.forLookup(normalized, store.internalCodeLength());
    return new BarcodeResolution(internalProduct(typedCode, normalized), null);
  }

  /** Produto do código interno; sem produto, o 404 carrega o código como o cliente o mandou. */
  private ProductSummary internalProduct(String internalCode, String barcode) {
    return productStore.findByInternalCode(internalCode).orElseThrow(() -> notFound(barcode));
  }

  /** Loja atual, dona dos parâmetros da etiqueta — o mesmo padrão dos demais casos de uso. */
  private Store currentStore() {
    return storeLookup
        .findByCode(defaultStoreCode)
        .orElseThrow(
            () -> new IllegalStateException("loja configurada não existe: " + defaultStoreCode));
  }

  /**
   * 404 do código que não resolve produto: o mesmo para o bipe (código desconhecido ou produto
   * inativo) e para o item da venda (código desconhecido).
   */
  public static NotFoundException notFound(String barcode) {
    return new NotFoundException(
        ErrorCode.PRODUCT_NOT_FOUND,
        "produto com código de barras %s não encontrado".formatted(barcode));
  }
}
