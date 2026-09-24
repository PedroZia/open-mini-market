package com.minimarket.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.sales.domain.Sale;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.ForbiddenException;
import com.minimarket.shared.domain.NotFoundException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link UnlinkCustomerUseCase}, sem Quarkus e sem banco: as portas são dublês
 * escritos à mão e o agregado é o de verdade. O desvínculo não consulta o cadastro — o cliente que
 * sai vai no evento pelo id —, então o teste não precisa de dublê de {@code CustomerStore}.
 */
class UnlinkCustomerUseCaseTest {

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
  private static final Instant NOW = Instant.parse("2026-09-24T13:00:00Z");

  private final FakeSaleStore saleStore = new FakeSaleStore();
  private final FakeAuditRecorder auditRecorder = new FakeAuditRecorder();

  private UnlinkCustomerUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new UnlinkCustomerUseCase();
    SaleAccessGuard saleAccessGuard = new SaleAccessGuard();
    saleAccessGuard.saleStore = saleStore;
    useCase.saleAccessGuard = saleAccessGuard;
    useCase.saleStore = saleStore;
    useCase.auditRecorder = auditRecorder;
    saleStore.sale = openSaleWithCustomer();
  }

  @Test
  @DisplayName("desvincula o cliente, grava a venda e audita SALE_CUSTOMER_UNLINKED")
  void unlinksCustomerAndAudits() {
    Sale sale = useCase.execute(new UnlinkCustomerCommand(SALE_ID, CASH_REGISTER_ID));

    assertThat(sale.customerId()).isNull();
    assertThat(saleStore.updated).as("o agregado alterado é o que vai para o banco").isSameAs(sale);
    assertThat(saleStore.updateCount).isEqualTo(1);

    FakeAuditRecorder.Event event = auditRecorder.only();
    assertThat(event.action()).isEqualTo("SALE_CUSTOMER_UNLINKED");
    assertThat(event.entityType()).isEqualTo("SALE");
    assertThat(event.entityId()).isEqualTo(SALE_ID);
    assertThat(event.reason()).as("o desvínculo não tem motivo humano").isNull();
    assertThat(event.details()).containsEntry("customerId", CUSTOMER_ID);
  }

  @Test
  @DisplayName("venda sem cliente é no-op: devolve o agregado sem gravar nem auditar")
  void unlinkWithoutCustomerIsNoOp() {
    saleStore.sale = openSale();

    Sale sale = useCase.execute(new UnlinkCustomerCommand(SALE_ID, CASH_REGISTER_ID));

    assertThat(sale).isSameAs(saleStore.sale);
    assertThat(sale.customerId()).isNull();
    assertThat(saleStore.updateCount).as("no-op não grava").isZero();
    assertThat(auditRecorder.recorded).as("no-op não inventa evento").isEmpty();
  }

  @Test
  @DisplayName("venda concluída lança ConflictException(SALE_NOT_OPEN) antes de mutar o agregado")
  void rejectsCompletedSale() {
    saleStore.sale.complete(NOW);

    assertThatThrownBy(() -> useCase.execute(new UnlinkCustomerCommand(SALE_ID, CASH_REGISTER_ID)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.SALE_NOT_OPEN));

    assertThat(saleStore.sale.customerId())
        .as("o cliente da venda concluída fica onde está")
        .isEqualTo(CUSTOMER_ID);
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("venda de outro caixa lança ForbiddenException(ACCESS_DENIED) sem tocar no agregado")
  void deniesSaleOfAnotherRegister() {
    assertThatThrownBy(
            () -> useCase.execute(new UnlinkCustomerCommand(SALE_ID, ANOTHER_REGISTER_ID)))
        .isInstanceOfSatisfying(
            ForbiddenException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.ACCESS_DENIED);
              assertThat(error.getMessage()).contains(SALE_ID.toString());
            });

    assertThat(saleStore.sale.customerId()).isEqualTo(CUSTOMER_ID);
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("venda inexistente lança NotFoundException(SALE_NOT_FOUND) sem gravar")
  void rejectsUnknownSale() {
    saleStore.sale = null;

    assertThatThrownBy(() -> useCase.execute(new UnlinkCustomerCommand(SALE_ID, CASH_REGISTER_ID)))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.SALE_NOT_FOUND);
              assertThat(error.getMessage()).contains(SALE_ID.toString());
            });

    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  /** Venda aberta e vazia, como o 805 a cria. */
  private static Sale openSale() {
    return new Sale(
        SALE_ID, STORE_ID, 7L, CASH_SESSION_ID, CASH_REGISTER_ID, OPERATOR_ID, null, NOW);
  }

  /** Venda aberta com o cliente do cenário vinculado. */
  private static Sale openSaleWithCustomer() {
    Sale sale = openSale();
    sale.linkCustomer(CUSTOMER_ID);
    return sale;
  }
}
