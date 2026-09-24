package com.minimarket.customers.application;

import com.minimarket.audit.application.AuditRecorder;
import com.minimarket.shared.application.StoreLookup;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Cria cliente (passo 502b): normaliza e valida o CPF, normaliza o telefone para dígitos e recusa
 * documento já usado por cliente vivo. Uma execução = uma transação (§2.2, regra 6): as checagens,
 * o insert e o evento vivem juntos, e o adaptador ainda traduz o 23505 do índice único parcial
 * {@code ux_customers_tax_id} como backstop caso outra requisição grave o mesmo CPF no meio do
 * caminho.
 *
 * <p>A loja não vem do cliente: é a configurada ({@code minimarket.store.default-code}), como no
 * {@code CreateProductUseCase} — o MVP tem loja única (§5.3). Cliente sem CPF é permitido (o campo
 * é opcional); CPF informado precisa ter os dois dígitos verificadores válidos, senão é 400 {@code
 * VALIDATION_ERROR}.
 *
 * <p>Auditoria: a criação vira {@code CUSTOMER_CREATED} na mesma transação, com o id criado em
 * {@code entityId} e name/taxId em {@code details} — o "after" da operação.
 */
@ApplicationScoped
public class CreateCustomerUseCase {

  /** Ação do cliente criado (§7.2). */
  private static final String CUSTOMER_CREATED_ACTION = "CUSTOMER_CREATED";

  /** Alvo dos eventos de cliente (§7.2). */
  private static final String CUSTOMER_ENTITY_TYPE = "CUSTOMER";

  @Inject CustomerStore customerStore;

  @Inject StoreLookup storeLookup;

  /** Auditoria do cadastro (passo 502b), na transação da criação. */
  @Inject AuditRecorder auditRecorder;

  @ConfigProperty(name = "minimarket.store.default-code")
  String defaultStoreCode;

  /** CPF é regra pura e sem estado: o caso de uso instancia, como manda o 502a. */
  private final TaxIdValidator taxIdValidator = new TaxIdValidator();

  /** Telefone guardado só em dígitos, para a busca do 502a alcançá-lo. */
  private final PhoneNormalizer phoneNormalizer = new PhoneNormalizer();

  /**
   * 400 {@code VALIDATION_ERROR} para CPF informado e inválido; 409 {@code TAX_ID_ALREADY_EXISTS}
   * quando o CPF já é de um cliente vivo.
   */
  @Transactional
  public CustomerSummary execute(CreateCustomerCommand command) {
    String taxId = requireValidTaxId(command.taxId());
    String phone = phoneNormalizer.normalize(command.phone());
    requireFreeTaxId(taxId);

    UUID id =
        customerStore.insert(
            new NewCustomer(
                currentStoreId(), command.name(), taxId, phone, command.email(), command.notes()));
    auditRecorder.record(
        CUSTOMER_CREATED_ACTION, CUSTOMER_ENTITY_TYPE, id, null, details(command.name(), taxId));

    return storedCustomer(id);
  }

  /** O cliente recém-inserido, com os defaults que o banco completou; o insert commita junto. */
  private CustomerSummary storedCustomer(UUID id) {
    return customerStore
        .findById(id)
        .orElseThrow(
            () ->
                new IllegalStateException("cliente %s não encontrado após o insert".formatted(id)));
  }

  /**
   * CPF em branco é cliente sem documento (nulo, como o índice único parcial ignora); informado, é
   * normalizado para dígitos e precisa ser um CPF de verdade.
   */
  private String requireValidTaxId(String taxId) {
    String normalized = taxIdValidator.normalize(taxId);
    if (normalized != null && !taxIdValidator.isValid(normalized)) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "CPF inválido");
    }
    return normalized;
  }

  /** Duplicidade vale só entre clientes vivos: o soft delete libera o CPF. */
  private void requireFreeTaxId(String taxId) {
    if (taxId != null && customerStore.existsActiveTaxId(taxId)) {
      throw new ConflictException(
          ErrorCode.TAX_ID_ALREADY_EXISTS, "CPF %s já está em uso".formatted(taxId));
    }
  }

  /** Cliente só existe dentro de uma loja; a loja atual vem da configuração, não do corpo. */
  private UUID currentStoreId() {
    return storeLookup
        .findByCode(defaultStoreCode)
        .orElseThrow(
            () -> new IllegalStateException("loja configurada não existe: " + defaultStoreCode))
        .id();
  }

  /**
   * Details mínimo do evento: o que identifica o cliente criado para quem lê o log depois. {@code
   * LinkedHashMap} porque cliente sem CPF é permitido e {@code Map.of} não aceita nulo.
   */
  private static Map<String, Object> details(String name, String taxId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("name", name);
    details.put("taxId", taxId);
    return details;
  }
}
