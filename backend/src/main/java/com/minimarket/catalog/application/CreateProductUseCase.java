package com.minimarket.catalog.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import com.minimarket.shared.domain.Store;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Cria produto (passo 405): normaliza o barcode pela regra única do {@link BarcodeNormalizer} e o
 * código interno pela do {@link InternalCodeNormalizer} (passo 1104d), exige preço não negativo,
 * unidade da whitelist {@code UN}/{@code KG} e categoria existente quando informada, e recusa
 * barcode ou código interno já usado por produto vivo. Uma execução = uma transação (§2.2, regra
 * 6): as checagens e o insert vivem juntos, e o adaptador ainda traduz o 23505 dos índices únicos
 * como backstop caso outra requisição grave o mesmo código no meio do caminho.
 *
 * <p>A loja não vem do cliente: é a configurada ({@code minimarket.store.default-code}), como no
 * {@code GetMetaUseCase} — o MVP tem loja única (§5.3). O produto nasce ativo e sem {@code
 * deletedAt}: o {@link NewProduct} não carrega status, então quem aplica o default da tabela é o
 * adaptador.
 *
 * <p>Auditoria (passo 405): a criação vira {@code PRODUCT_CREATED} na mesma transação, com o id
 * criado em {@code entityId} e name/barcode/price em {@code details} — o "after" da operação.
 *
 * <p>O resultado carrega o produto relido da porta (passo 406): a resposta 201 devolve o que o
 * banco guardou — defaults como {@code active}, timestamps e {@code version} — sem a API
 * renormalizar nada por conta própria.
 */
@ApplicationScoped
public class CreateProductUseCase {

  /** Unidades comerciais do MVP (§5.3); o check da coluna repete a mesma lista. */
  private static final Set<String> UNITS = Set.of("UN", "KG");

  /** Ação do produto criado (§7.2). */
  private static final String PRODUCT_CREATED_ACTION = "PRODUCT_CREATED";

  /** Alvo dos eventos de catálogo (§7.2). */
  private static final String PRODUCT_ENTITY_TYPE = "PRODUCT";

  @Inject ProductStore productStore;

  @Inject CategoryStore categoryStore;

  @Inject StoreLookup storeLookup;

  /** Auditoria do catálogo (passo 405), na transação da criação. */
  @Inject AuditRecorder auditRecorder;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  /**
   * 409 {@code BARCODE_ALREADY_EXISTS} quando o barcode já é de um produto vivo, 409 {@code
   * INTERNAL_CODE_ALREADY_EXISTS} quando o código interno já é de outro produto vivo; 404 {@code
   * CATEGORY_NOT_FOUND} quando a categoria informada não existe; 400 {@code VALIDATION_ERROR} para
   * preço ausente/negativo, unidade fora da whitelist e código interno não numérico ou maior que o
   * configurado (estes dois com o campo {@code internalCode} em {@code errors[]}).
   */
  @Transactional
  public CreateProductResult execute(CreateProductCommand command) {
    Store store = currentStore();
    String barcode = BarcodeNormalizer.normalize(command.barcode());
    String internalCode =
        InternalCodeNormalizer.normalize(command.internalCode(), store.internalCodeLength());
    BigDecimal price = requireValidPrice(command.price());
    String unit = requireValidUnit(command.unit());
    requireExistingCategory(command.categoryId());
    requireFreeBarcode(barcode);
    requireFreeInternalCode(internalCode);

    UUID id =
        productStore.insert(
            new NewProduct(
                store.id(),
                command.name(),
                barcode,
                internalCode,
                command.description(),
                command.categoryId(),
                unit,
                price,
                command.minQuantity()));
    auditRecorder.record(
        PRODUCT_CREATED_ACTION,
        PRODUCT_ENTITY_TYPE,
        id,
        null,
        details(command.name(), barcode, internalCode, price));

    return new CreateProductResult(id, storedProduct(id));
  }

  /** O produto recém-inserido, com os defaults que o banco completou; o insert commita junto. */
  private ProductSummary storedProduct(UUID id) {
    return productStore
        .findById(id)
        .orElseThrow(
            () ->
                new IllegalStateException("produto %s não encontrado após o insert".formatted(id)));
  }

  /** Dinheiro entra com duas casas (§4.4), arredondando como as contas de venda. */
  private static BigDecimal requireValidPrice(BigDecimal price) {
    if (price == null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "preço é obrigatório");
    }
    if (price.signum() < 0) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "preço não pode ser negativo");
    }
    return price.setScale(2, RoundingMode.HALF_UP);
  }

  /** A unidade é a string que o check da coluna aceita; nada de enum de infrastructure aqui. */
  private static String requireValidUnit(String unit) {
    if (unit == null || !UNITS.contains(unit)) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "unidade deve ser UN ou KG");
    }
    return unit;
  }

  /** Categoria nula é produto sem categoria (§5.3); informada, precisa existir. */
  private void requireExistingCategory(UUID categoryId) {
    if (categoryId != null && !categoryStore.existsById(categoryId)) {
      throw new NotFoundException(
          ErrorCode.CATEGORY_NOT_FOUND, "categoria %s não encontrada".formatted(categoryId));
    }
  }

  /** Duplicidade vale só entre produtos vivos: o soft-deletado libera o barcode. */
  private void requireFreeBarcode(String barcode) {
    if (barcode != null && productStore.existsActiveBarcode(barcode)) {
      throw new ConflictException(
          ErrorCode.BARCODE_ALREADY_EXISTS,
          "código de barras %s já está em uso".formatted(barcode));
    }
  }

  /** Mesma regra do barcode para o código interno da etiqueta (passo 1104d). */
  private void requireFreeInternalCode(String internalCode) {
    if (internalCode != null && productStore.existsActiveInternalCode(internalCode)) {
      throw new ConflictException(
          ErrorCode.INTERNAL_CODE_ALREADY_EXISTS,
          "código interno %s já está em uso".formatted(internalCode));
    }
  }

  /** Produto só existe dentro de uma loja; a loja atual vem da configuração, não do corpo. */
  private Store currentStore() {
    return storeLookup
        .findByCode(defaultStoreCode)
        .orElseThrow(
            () -> new IllegalStateException("loja configurada não existe: " + defaultStoreCode));
  }

  /** Details mínimo do evento: o que identifica o produto criado para quem lê o log depois. */
  private static Map<String, Object> details(
      String name, String barcode, String internalCode, BigDecimal price) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("name", name);
    details.put("barcode", barcode);
    details.put("internalCode", internalCode);
    details.put("price", price);
    return details;
  }
}
