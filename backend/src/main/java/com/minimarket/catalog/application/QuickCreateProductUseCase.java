package com.minimarket.catalog.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
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
 * Cadastro rápido do PDV (F-02, passo 1122): cria o produto desconhecido sem sair da venda, com a
 * permissão própria {@code product.quick_create} — o OPERADOR cadastra o essencial sem ganhar o
 * {@code product.write} da edição do catálogo.
 *
 * <p>O contrato é enxuto de propósito: nome, código de barras (obrigatório aqui, ao contrário do
 * cadastro do passo 405: é o código lido que abriu o modal), preço e unidade. Normaliza o barcode
 * pela mesma regra única do {@link BarcodeNormalizer}, exige preço não negativo e unidade da
 * whitelist {@code UN}/{@code KG} — as mesmas validações do cadastro completo, repetidas aqui como
 * no {@link UpdateProductUseCase} — e recusa barcode de produto vivo. Uma execução = uma transação
 * (§2.2, regra 6): as checagens e o insert vivem juntos, e o {@link ProductStore} ainda traduz o
 * 23505 do índice único parcial como backstop caso outra requisição grave o mesmo código no meio.
 *
 * <p>A loja não vem do cliente: é a configurada ({@code minimarket.store.default-code}), como no
 * cadastro completo. O produto nasce ativo, sem soft delete, sem categoria, sem descrição, sem
 * código interno e sem mínimo — quem aplica os defaults é o adaptador.
 *
 * <p>Auditoria: a criação vira {@code PRODUCT_QUICK_CREATED} na mesma transação, com o id criado em
 * {@code entityId} e name/barcode/price/unit em {@code details} — o "after" da operação. A ação é
 * distinta de {@code PRODUCT_CREATED} porque a origem é outra (balcão, operador, sem a edição do
 * catálogo) e quem lê o log depois precisa conseguir separar as duas.
 *
 * <p>O produto devolvido é relido da porta, como no 406: a resposta 201 mostra o que o banco
 * guardou — barcode normalizado, preço em escala 2, {@code active}, timestamps e {@code version} —
 * sem a API renormalizar nada por conta própria.
 */
@ApplicationScoped
public class QuickCreateProductUseCase {

  /** Unidades comerciais do MVP (§5.3); o check da coluna repete a mesma lista. */
  private static final Set<String> UNITS = Set.of("UN", "KG");

  /** Ação do produto criado pelo caixa (§7.2). */
  private static final String PRODUCT_QUICK_CREATED_ACTION = "PRODUCT_QUICK_CREATED";

  /** Alvo dos eventos de catálogo (§7.2). */
  private static final String PRODUCT_ENTITY_TYPE = "PRODUCT";

  @Inject ProductStore productStore;

  @Inject StoreLookup storeLookup;

  /** Auditoria do catálogo, na transação da criação. */
  @Inject AuditRecorder auditRecorder;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  /**
   * 409 {@code BARCODE_ALREADY_EXISTS} quando o barcode já é de um produto vivo; 400 {@code
   * VALIDATION_ERROR} para barcode vazio, preço ausente/negativo e unidade fora da whitelist.
   */
  @Transactional
  public ProductSummary execute(QuickCreateProductCommand command) {
    Store store = currentStore();
    String barcode = requireBarcode(BarcodeNormalizer.normalize(command.barcode()));
    BigDecimal price = requireValidPrice(command.price());
    String unit = requireValidUnit(command.unit());
    requireFreeBarcode(barcode);

    UUID id =
        productStore.insert(
            new NewProduct(store.id(), command.name(), barcode, null, null, unit, price, null));
    auditRecorder.record(
        PRODUCT_QUICK_CREATED_ACTION,
        PRODUCT_ENTITY_TYPE,
        id,
        null,
        details(command.name(), barcode, price, unit));

    return storedProduct(id);
  }

  /** O produto recém-inserido, com os defaults que o banco completou; o insert commita junto. */
  private ProductSummary storedProduct(UUID id) {
    return productStore
        .findById(id)
        .orElseThrow(
            () ->
                new IllegalStateException("produto %s não encontrado após o insert".formatted(id)));
  }

  /** Aqui o código é obrigatório: é a leitura desconhecida que abre o cadastro rápido. */
  private static String requireBarcode(String barcode) {
    if (barcode == null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "código de barras é obrigatório");
    }
    return barcode;
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

  /** Duplicidade vale só entre produtos vivos: o soft-deletado libera o barcode. */
  private void requireFreeBarcode(String barcode) {
    if (productStore.existsActiveBarcode(barcode)) {
      throw new ConflictException(
          ErrorCode.BARCODE_ALREADY_EXISTS,
          "código de barras %s já está em uso".formatted(barcode));
    }
  }

  /** Produto só existe dentro de uma loja; a loja atual vem da configuração, não do corpo. */
  private Store currentStore() {
    return storeLookup
        .findByCode(defaultStoreCode)
        .orElseThrow(
            () -> new IllegalStateException("loja configurada não existe: " + defaultStoreCode));
  }

  /** Details mínimo do evento: o que identifica o produto criado pelo caixa. */
  private static Map<String, Object> details(
      String name, String barcode, BigDecimal price, String unit) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("name", name);
    details.put("barcode", barcode);
    details.put("price", price);
    details.put("unit", unit);
    return details;
  }
}
