package com.minimarket.catalog.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Edita o cadastro do produto (passo 410) com lock otimista: só grava se a versão que o cliente leu
 * no {@code If-Match} ainda for a do banco — a segunda edição do mesmo produto recebe 409 (§8 do
 * plano) em vez de sobrescrever a primeira. Uma execução = uma transação (§2.2, regra 6): a leitura
 * da versão, as validações e a gravação vivem juntas, e o adaptador ainda traduz o stale do
 * Hibernate como backstop caso outra requisição grave entre a checagem e o flush.
 *
 * <p>Só entram produto vivo e ativo: {@code deletedAt} preenchido ou {@code active} falso é 404
 * {@code PRODUCT_NOT_FOUND}, como no detalhe (passo 408) — o passo 412 é quem reativa.
 *
 * <p>Auditoria (passo 410, consolidada no 413): a alteração vira {@code PRODUCT_UPDATED} na mesma
 * transação, com o antes/depois mínimo dos cinco campos editados (§7.2). O "before" é lido antes de
 * gravar — depois do update a projeção já viria com os valores novos.
 */
@ApplicationScoped
public class UpdateProductUseCase {

  /** Unidades comerciais do MVP (§5.3); o check da coluna repete a mesma lista. */
  private static final Set<String> UNITS = Set.of("UN", "KG");

  /** Ação do produto alterado (§7.2). */
  private static final String PRODUCT_UPDATED_ACTION = "PRODUCT_UPDATED";

  /** Alvo dos eventos de catálogo (§7.2). */
  private static final String PRODUCT_ENTITY_TYPE = "PRODUCT";

  @Inject ProductStore productStore;

  @Inject CategoryStore categoryStore;

  /** Auditoria do catálogo (passo 410), na transação da alteração. */
  @Inject AuditRecorder auditRecorder;

  /**
   * 404 {@code PRODUCT_NOT_FOUND} para id desconhecido, produto soft-deletado ou desativado; 409
   * {@code CONCURRENT_MODIFICATION} quando a versão esperada não é a do banco; 400 {@code
   * VALIDATION_ERROR} para unidade fora da whitelist; 404 {@code CATEGORY_NOT_FOUND} quando a
   * categoria informada não existe. Devolve o produto como o banco o guardou, com o {@code version}
   * novo para o {@code If-Match} seguinte.
   */
  @Transactional
  public ProductSummary execute(UpdateProductCommand command) {
    ProductSummary before = requireEditable(command.id());
    requireExpectedVersion(before, command.expectedVersion());
    String unit = requireValidUnit(command.unit());
    requireExistingCategory(command.categoryId());

    ProductSummary after =
        productStore
            .update(
                command.id(),
                command.name(),
                command.categoryId(),
                unit,
                command.description(),
                command.minQuantity())
            .orElseThrow(() -> notFound(command.id()));
    auditRecorder.record(
        PRODUCT_UPDATED_ACTION,
        PRODUCT_ENTITY_TYPE,
        command.id(),
        null,
        beforeAndAfter(before, after));
    return after;
  }

  /** Produto vivo e ativo; o que não está no catálogo conta como inexistente, como no passo 408. */
  private ProductSummary requireEditable(UUID id) {
    return productStore
        .findById(id)
        .filter(product -> product.deletedAt() == null && product.active())
        .orElseThrow(() -> notFound(id));
  }

  /**
   * A versão do banco tem que ser a que o cliente mandou no {@code If-Match}. Divergiu, alguém
   * gravou no meio: 409 sem tocar na linha — o que o cliente leu continua valendo para ele.
   */
  private static void requireExpectedVersion(ProductSummary product, long expectedVersion) {
    if (product.version() != expectedVersion) {
      throw new ConflictException(
          ErrorCode.CONCURRENT_MODIFICATION,
          "produto %s foi alterado (versão esperada %d, atual %d); recarregue e tente de novo"
              .formatted(product.id(), expectedVersion, product.version()));
    }
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

  /**
   * O antes/depois mínimo do §7.2: só os campos que a operação muda, nunca o produto inteiro.
   * {@code LinkedHashMap} porque categoria, descrição e quantidade mínima podem ser nulas — valor
   * nulo no jsonb é o produto sem aquele dado, e {@code Map.of} não aceita nulo.
   */
  private static Map<String, Object> beforeAndAfter(ProductSummary before, ProductSummary after) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("before", snapshot(before));
    details.put("after", snapshot(after));
    return details;
  }

  private static Map<String, Object> snapshot(ProductSummary product) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("name", product.name());
    values.put("categoryId", product.categoryId());
    values.put("unit", product.unit());
    values.put("description", product.description());
    values.put("minQuantity", product.minQuantity());
    return values;
  }

  private static NotFoundException notFound(UUID id) {
    return new NotFoundException(
        ErrorCode.PRODUCT_NOT_FOUND, "produto %s não encontrado".formatted(id));
  }
}
