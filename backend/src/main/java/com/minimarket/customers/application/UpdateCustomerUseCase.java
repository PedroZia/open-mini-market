package com.minimarket.customers.application;

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
import java.util.Objects;
import java.util.UUID;

/**
 * Edita o cadastro do cliente (passo 502b), sem {@code If-Match} por decisão registrada do passo: o
 * lock otimista do repositório ({@code @Version}) é o backstop — duas edições simultâneas não se
 * sobrescrevem em silêncio, a segunda vira 409 {@code CONCURRENT_MODIFICATION}. Uma execução = uma
 * transação (§2.2, regra 6): a leitura do antes, as validações, a gravação e o evento vivem juntas.
 *
 * <p>Só entra cliente vivo: desativado nunca aparece na porta, então 404 {@code CUSTOMER_NOT_FOUND}
 * cobre o id desconhecido e o já desativado, como no detalhe.
 *
 * <p>CPF igual ao que o próprio cliente já tem não conflita (não há corrida com ele mesmo);
 * diferente e já tomado por outro cliente vivo é 409 {@code TAX_ID_ALREADY_EXISTS}, como no
 * cadastro. Telefone e CPF são normalizados antes de comparar e gravar.
 *
 * <p>Atualização sem mudança efetiva é no-op: responde 200 com o cliente como está, sem gravar nem
 * auditar — mesmo precedente do preço igual (passo 411) e do enable de produto já ativo (passo
 * 412).
 *
 * <p>Auditoria: a alteração que de fato acontece vira {@code CUSTOMER_UPDATED} na mesma transação,
 * com o antes/depois mínimo dos cinco campos editados (§7.2). O "before" é lido antes de gravar —
 * depois do update a projeção já viria com os valores novos.
 */
@ApplicationScoped
public class UpdateCustomerUseCase {

  /** Ação do cliente alterado (§7.2). */
  private static final String CUSTOMER_UPDATED_ACTION = "CUSTOMER_UPDATED";

  /** Alvo dos eventos de cliente (§7.2). */
  private static final String CUSTOMER_ENTITY_TYPE = "CUSTOMER";

  @Inject CustomerStore customerStore;

  /** Auditoria da alteração (passo 502b), na transação da edição. */
  @Inject AuditRecorder auditRecorder;

  /** CPF é regra pura e sem estado: o caso de uso instancia, como manda o 502a. */
  private final TaxIdValidator taxIdValidator = new TaxIdValidator();

  /** Telefone guardado só em dígitos, para a busca do 502a alcançá-lo. */
  private final PhoneNormalizer phoneNormalizer = new PhoneNormalizer();

  /**
   * 404 {@code CUSTOMER_NOT_FOUND} para id desconhecido ou cliente já desativado; 400 {@code
   * VALIDATION_ERROR} para CPF informado e inválido; 409 {@code TAX_ID_ALREADY_EXISTS} quando o CPF
   * novo já é de outro cliente vivo. Devolve o cliente como o banco o guardou, com o {@code
   * version} novo.
   */
  @Transactional
  public CustomerSummary execute(UpdateCustomerCommand command) {
    CustomerSummary before = requireEditable(command.id());
    String taxId = requireValidTaxId(command.taxId());
    String phone = phoneNormalizer.normalize(command.phone());
    if (isNoOp(before, command, taxId, phone)) {
      return before;
    }
    requireFreeTaxId(taxId, before.taxId());

    CustomerSummary after =
        customerStore
            .update(command.id(), command.name(), taxId, phone, command.email(), command.notes())
            .orElseThrow(() -> notFound(command.id()));
    auditRecorder.record(
        CUSTOMER_UPDATED_ACTION,
        CUSTOMER_ENTITY_TYPE,
        command.id(),
        null,
        beforeAndAfter(before, after));
    return after;
  }

  /** Cliente vivo; o desativado conta como inexistente, como no detalhe. */
  private CustomerSummary requireEditable(UUID id) {
    return customerStore.findById(id).orElseThrow(() -> notFound(id));
  }

  /**
   * CPF em branco é cliente sem documento (nulo); informado, é normalizado para dígitos e precisa
   * ser um CPF de verdade.
   */
  private String requireValidTaxId(String taxId) {
    String normalized = taxIdValidator.normalize(taxId);
    if (normalized != null && !taxIdValidator.isValid(normalized)) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "CPF inválido");
    }
    return normalized;
  }

  /**
   * O CPF do próprio cliente não conflita consigo mesmo; tomado por outro vivo, é 409. Nulo não tem
   * duplicidade a checar (o índice único ignora os sem documento).
   */
  private void requireFreeTaxId(String taxId, String currentTaxId) {
    if (taxId == null || taxId.equals(currentTaxId)) {
      return;
    }
    if (customerStore.existsActiveTaxId(taxId)) {
      throw new ConflictException(
          ErrorCode.TAX_ID_ALREADY_EXISTS, "CPF %s já está em uso".formatted(taxId));
    }
  }

  /**
   * Nada mudou de verdade: os valores normalizados são iguais aos do cliente. Não há alteração a
   * gravar nem evento a inventar — e a comparação é sobre os valores normalizados, então trocar a
   * máscara do CPF ou do telefone não inventa edição.
   */
  private static boolean isNoOp(
      CustomerSummary before, UpdateCustomerCommand command, String taxId, String phone) {
    return Objects.equals(before.name(), command.name())
        && Objects.equals(before.taxId(), taxId)
        && Objects.equals(before.phone(), phone)
        && Objects.equals(before.email(), command.email())
        && Objects.equals(before.notes(), command.notes());
  }

  /**
   * O antes/depois mínimo do §7.2: só os campos que a operação muda, nunca o cliente inteiro.
   * {@code LinkedHashMap} porque todos os cinco campos podem ser nulos — valor nulo no jsonb é o
   * cliente sem aquele dado, e {@code Map.of} não aceita nulo.
   */
  private static Map<String, Object> beforeAndAfter(CustomerSummary before, CustomerSummary after) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("before", snapshot(before));
    details.put("after", snapshot(after));
    return details;
  }

  private static Map<String, Object> snapshot(CustomerSummary customer) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("name", customer.name());
    values.put("taxId", customer.taxId());
    values.put("phone", customer.phone());
    values.put("email", customer.email());
    values.put("notes", customer.notes());
    return values;
  }

  private static NotFoundException notFound(UUID id) {
    return new NotFoundException(
        ErrorCode.CUSTOMER_NOT_FOUND, "cliente %s não encontrado".formatted(id));
  }
}
