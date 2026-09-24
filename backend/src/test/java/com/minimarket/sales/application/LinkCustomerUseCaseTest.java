package com.minimarket.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.customers.application.CustomerSummary;
import com.minimarket.sales.domain.Sale;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.ForbiddenException;
import com.minimarket.shared.domain.NotFoundException;
import java.time.Instant;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link LinkCustomerUseCase}, sem Quarkus e sem banco: as portas são dublês
 * escritos à mão e o agregado é o de verdade. O cliente vem do {@link FakeCustomerStore} — o
 * cenário do desativado é coberto sem subir o cadastro do Quarkus.
 */
class LinkCustomerUseCaseTest {

  private static final UUID STORE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000002");
  private static final UUID SALE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000030");
  private static final UUID CASH_SESSION_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000020");
  private static final UUID CASH_REGISTER_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000010");
  private static final UUID ANOTHER_REGISTER_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000011");
  private static final UUID OPERATOR_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000001");
  private static final UUID CUSTOMER_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000050");
  private static final UUID ANOTHER_CUSTOMER_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000051");
  private static final Instant NOW = Instant.parse("2026-09-24T13:00:00Z");

  private final FakeSaleStore saleStore = new FakeSaleStore();
  private final FakeCustomerStore customerStore = new FakeCustomerStore();
  private final FakeAuditRecorder auditRecorder = new FakeAuditRecorder();

  private LinkCustomerUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new LinkCustomerUseCase();
    SaleAccessGuard saleAccessGuard = new SaleAccessGuard();
    saleAccessGuard.saleStore = saleStore;
    useCase.saleAccessGuard = saleAccessGuard;
    useCase.saleStore = saleStore;
    useCase.customerStore = customerStore;
    useCase.auditRecorder = auditRecorder;
    saleStore.sale = openSale();
    customerStore.customer = customer(CUSTOMER_ID, "Ana Souza", true);
  }

  @Test
  @DisplayName("vincula o cliente ativo, grava a venda e audita SALE_CUSTOMER_LINKED")
  void linksActiveCustomerAndAudits() {
    Sale sale = useCase.execute(new LinkCustomerCommand(SALE_ID, CASH_REGISTER_ID, CUSTOMER_ID));

    assertThat(sale.customerId()).isEqualTo(CUSTOMER_ID);
    assertThat(saleStore.updated).as("o agregado alterado é o que vai para o banco").isSameAs(sale);
    assertThat(saleStore.updateCount).isEqualTo(1);

    FakeAuditRecorder.Event event = auditRecorder.only();
    assertThat(event.action()).isEqualTo("SALE_CUSTOMER_LINKED");
    assertThat(event.entityType()).isEqualTo("SALE");
    assertThat(event.entityId()).isEqualTo(SALE_ID);
    assertThat(event.reason())
        .as("o vínculo não tem motivo humano, como a inclusão de item")
        .isNull();
    assertThat(event.details())
        .containsEntry("customerId", CUSTOMER_ID)
        .containsEntry("customerName", "Ana Souza");
  }

  @Test
  @DisplayName("vincular outro cliente substitui o anterior e audita a troca")
  void replacesPreviousCustomer() {
    saleStore.sale.linkCustomer(CUSTOMER_ID);
    customerStore.customer = customer(ANOTHER_CUSTOMER_ID, "Bruno Lima", true);

    Sale sale =
        useCase.execute(new LinkCustomerCommand(SALE_ID, CASH_REGISTER_ID, ANOTHER_CUSTOMER_ID));

    assertThat(sale.customerId())
        .as("a venda guarda um cliente por vez")
        .isEqualTo(ANOTHER_CUSTOMER_ID);
    assertThat(auditRecorder.only().details()).containsEntry("customerName", "Bruno Lima");
  }

  @Test
  @DisplayName("cliente desativado lança BusinessException(CUSTOMER_INACTIVE) sem gravar")
  void rejectsInactiveCustomer() {
    customerStore.customer = customer(CUSTOMER_ID, "Ana Souza", false);

    assertThatThrownBy(
            () -> useCase.execute(new LinkCustomerCommand(SALE_ID, CASH_REGISTER_ID, CUSTOMER_ID)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.CUSTOMER_INACTIVE);
              assertThat(error.getMessage()).contains(CUSTOMER_ID.toString());
            });

    assertThat(saleStore.sale.customerId()).as("a venda não é tocada na recusa").isNull();
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("cliente inexistente lança NotFoundException(CUSTOMER_NOT_FOUND) sem gravar")
  void rejectsUnknownCustomer() {
    customerStore.customer = null;

    assertThatThrownBy(
            () -> useCase.execute(new LinkCustomerCommand(SALE_ID, CASH_REGISTER_ID, CUSTOMER_ID)))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.CUSTOMER_NOT_FOUND);
              assertThat(error.getMessage()).contains(CUSTOMER_ID.toString());
            });

    assertThat(saleStore.sale.customerId()).isNull();
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("cliente ausente no comando lança BusinessException(VALIDATION_ERROR) sem gravar")
  void rejectsMissingCustomer() {
    assertValidationError(
        () -> useCase.execute(new LinkCustomerCommand(SALE_ID, CASH_REGISTER_ID, null)),
        "cliente é obrigatório");

    assertThat(saleStore.sale.customerId()).isNull();
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("venda concluída lança ConflictException(SALE_NOT_OPEN) antes de ler o cliente")
  void rejectsCompletedSale() {
    saleStore.sale.complete(NOW);
    customerStore.customer = null;

    assertThatThrownBy(
            () -> useCase.execute(new LinkCustomerCommand(SALE_ID, CASH_REGISTER_ID, CUSTOMER_ID)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.SALE_NOT_OPEN));

    assertThat(saleStore.sale.customerId()).isNull();
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("venda de outro caixa lança ForbiddenException(ACCESS_DENIED) sem tocar no agregado")
  void deniesSaleOfAnotherRegister() {
    assertThatThrownBy(
            () ->
                useCase.execute(new LinkCustomerCommand(SALE_ID, ANOTHER_REGISTER_ID, CUSTOMER_ID)))
        .isInstanceOfSatisfying(
            ForbiddenException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.ACCESS_DENIED);
              assertThat(error.getMessage()).contains(SALE_ID.toString());
            });

    assertThat(saleStore.sale.customerId()).isNull();
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("venda inexistente lança NotFoundException(SALE_NOT_FOUND) sem gravar")
  void rejectsUnknownSale() {
    saleStore.sale = null;

    assertThatThrownBy(
            () -> useCase.execute(new LinkCustomerCommand(SALE_ID, CASH_REGISTER_ID, CUSTOMER_ID)))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.SALE_NOT_FOUND);
              assertThat(error.getMessage()).contains(SALE_ID.toString());
            });

    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  /** Forma inválida do comando: 400 {@code VALIDATION_ERROR} antes de qualquer gravação. */
  private static void assertValidationError(ThrowingCallable call, String messageFragment) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
              assertThat(error.getMessage()).contains(messageFragment);
            });
  }

  /** Venda aberta e vazia, como o 805 a cria. */
  private static Sale openSale() {
    return new Sale(
        SALE_ID, STORE_ID, 7L, CASH_SESSION_ID, CASH_REGISTER_ID, OPERATOR_ID, null, NOW);
  }

  /** Cliente do cenário: ativo com timestamps preenchidos, ou desativado com {@code deletedAt}. */
  private static CustomerSummary customer(UUID id, String name, boolean active) {
    Instant now = NOW;
    return new CustomerSummary(
        id,
        STORE_ID,
        name,
        "11144477735",
        "11912345678",
        null,
        null,
        active,
        now,
        now,
        active ? null : now,
        active ? 0L : 1L);
  }
}
