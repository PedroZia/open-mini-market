package com.minimarket.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleStatus;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ConflictException;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.ForbiddenException;
import com.minimarket.shared.domain.NotFoundException;
import com.minimarket.shared.domain.Permission;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link CancelSaleUseCase}, sem Quarkus e sem banco: as portas são dublês
 * escritos à mão, o agregado é o de verdade e o relógio é fixo — o instante do cancelamento é
 * conferido como o caso de uso o gravou. O gravador de auditoria também é dublê: o evento é
 * conferido como o caso de uso o entregou.
 */
class CancelSaleUseCaseTest {

  private static final UUID STORE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000002");
  private static final UUID SALE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000030");
  private static final UUID RICE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000040");
  private static final UUID BEANS_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000041");
  private static final UUID CASH_SESSION_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000020");
  private static final UUID CASH_REGISTER_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000010");
  private static final UUID ANOTHER_REGISTER_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000011");
  private static final UUID OPERATOR_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000001");
  private static final UUID MANAGER_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000003");
  private static final Instant OPENED_AT = Instant.parse("2026-09-24T12:00:00Z");
  private static final Instant CANCELLED_AT = Instant.parse("2026-09-24T13:00:00Z");

  private final FakeSaleStore saleStore = new FakeSaleStore();
  private final FakeAuditRecorder auditRecorder = new FakeAuditRecorder();
  private final FakeAuthorizationService authorizationService = new FakeAuthorizationService();

  private CancelSaleUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new CancelSaleUseCase();
    SaleAccessGuard saleAccessGuard = new SaleAccessGuard();
    saleAccessGuard.saleStore = saleStore;
    useCase.saleAccessGuard = saleAccessGuard;
    useCase.saleStore = saleStore;
    useCase.auditRecorder = auditRecorder;
    useCase.authorizationService = authorizationService;
    useCase.clock = Clock.fixed(CANCELLED_AT, ZoneOffset.UTC);
    authorizationService.granted.add(Permission.SALE_CANCEL);
    saleStore.sale = saleWithItems();
  }

  @Test
  @DisplayName(
      "cancela a venda aberta com motivo, autor e instante do relógio, e audita o estado final")
  void cancelsOpenSaleAndAudits() {
    Sale sale = useCase.execute(command("cliente desistiu"));

    assertThat(sale.status()).isEqualTo(SaleStatus.CANCELLED);
    assertThat(sale.cancelReason()).isEqualTo("cliente desistiu");
    assertThat(sale.cancelledByUserId())
        .as("o autor é o do comando, que a API monta do OperationContext")
        .isEqualTo(MANAGER_ID);
    assertThat(sale.cancelledAt()).as("o instante é o do Clock injetado").isEqualTo(CANCELLED_AT);
    assertThat(sale.completedAt()).isNull();
    assertThat(saleStore.updated)
        .as("o agregado cancelado é o que vai para o banco")
        .isSameAs(sale);
    assertThat(saleStore.updateCount).isEqualTo(1);

    FakeAuditRecorder.Event event = auditRecorder.only();
    assertThat(event.action()).isEqualTo("SALE_CANCELLED");
    assertThat(event.entityType()).isEqualTo("SALE");
    assertThat(event.entityId()).isEqualTo(SALE_ID);
    assertThat(event.reason()).as("o motivo vai no reason do evento").isEqualTo("cliente desistiu");
    assertThat(event.details())
        .containsEntry("status", "CANCELLED")
        .containsEntry("subtotal", new BigDecimal("28.30"))
        .containsEntry("discountAmount", new BigDecimal("0.00"))
        .containsEntry("total", new BigDecimal("28.30"))
        .containsEntry("itemCount", 2);
  }

  @Test
  @DisplayName("venda concluída lança ConflictException(SALE_ALREADY_COMPLETED) sem gravar")
  void rejectsCompletedSale() {
    saleStore.sale.complete(OPENED_AT.plusSeconds(60));

    assertThatThrownBy(() -> useCase.execute(command("desistiu")))
        .isInstanceOfSatisfying(
            ConflictException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.SALE_ALREADY_COMPLETED);
              assertThat(error.getMessage()).contains(SALE_ID.toString());
            });

    assertThat(saleStore.sale.status()).isEqualTo(SaleStatus.COMPLETED);
    assertThat(saleStore.sale.cancelReason()).isNull();
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("venda já cancelada é no-op: devolve a venda como está, sem gravar e sem evento")
  void treatsCancelledSaleAsNoOp() {
    saleStore.sale.cancel("primeiro motivo", OPERATOR_ID, OPENED_AT.plusSeconds(30));

    Sale sale = useCase.execute(command("segundo motivo"));

    assertThat(sale).as("a venda devolvida é a que está no banco").isSameAs(saleStore.sale);
    assertThat(sale.status()).isEqualTo(SaleStatus.CANCELLED);
    assertThat(sale.cancelReason())
        .as("o motivo do primeiro cancelamento é o que vale")
        .isEqualTo("primeiro motivo");
    assertThat(sale.cancelledByUserId()).isEqualTo(OPERATOR_ID);
    assertThat(sale.cancelledAt()).isEqualTo(OPENED_AT.plusSeconds(30));
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("motivo ausente ou em branco lança BusinessException(VALIDATION_ERROR) sem gravar")
  void rejectsBlankReason() {
    for (String reason : new String[] {null, "", "  "}) {
      assertThatThrownBy(() -> useCase.execute(command(reason)))
          .isInstanceOfSatisfying(
              BusinessException.class,
              error -> {
                assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                assertThat(error.getMessage()).contains("motivo do cancelamento");
              });
    }

    assertThat(saleStore.sale.status())
        .as("a recusa nem chega ao agregado")
        .isEqualTo(SaleStatus.OPEN);
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("venda de outro caixa lança ForbiddenException(ACCESS_DENIED) sem tocar no agregado")
  void deniesSaleOfAnotherRegister() {
    assertThatThrownBy(
            () ->
                useCase.execute(
                    new CancelSaleCommand(SALE_ID, ANOTHER_REGISTER_ID, "desistiu", MANAGER_ID)))
        .isInstanceOfSatisfying(
            ForbiddenException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.ACCESS_DENIED);
              assertThat(error.getMessage()).contains(SALE_ID.toString());
            });

    assertThat(saleStore.sale.status()).isEqualTo(SaleStatus.OPEN);
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("venda inexistente lança NotFoundException(SALE_NOT_FOUND) sem gravar")
  void rejectsUnknownSale() {
    saleStore.sale = null;

    assertThatThrownBy(() -> useCase.execute(command("desistiu")))
        .isInstanceOfSatisfying(
            NotFoundException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.SALE_NOT_FOUND);
              assertThat(error.getMessage()).contains(SALE_ID.toString());
            });

    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  @Test
  @DisplayName("sem sale.cancel lança ForbiddenException(ACCESS_DENIED) sem ler a venda")
  void deniesSessionWithoutPermission() {
    authorizationService.granted.clear();

    assertThatThrownBy(() -> useCase.execute(command("desistiu")))
        .isInstanceOfSatisfying(
            ForbiddenException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.ACCESS_DENIED);
              assertThat(error.getMessage()).contains("sale.cancel");
            });

    assertThat(saleStore.sale.status()).as("a venda nem é tocada").isEqualTo(SaleStatus.OPEN);
    assertThat(saleStore.updateCount).isZero();
    assertThat(auditRecorder.recorded).isEmpty();
  }

  /** Comando do cenário: o caixa da sessão e o autor vêm da API, nunca do corpo. */
  private static CancelSaleCommand command(String reason) {
    return new CancelSaleCommand(SALE_ID, CASH_REGISTER_ID, reason, MANAGER_ID);
  }

  /** Venda aberta com dois itens, como o 808 a deixa depois de dois bipes. */
  private static Sale saleWithItems() {
    Sale sale =
        new Sale(
            SALE_ID, STORE_ID, 7L, CASH_SESSION_ID, CASH_REGISTER_ID, OPERATOR_ID, null, OPENED_AT);
    sale.addItem(
        RICE_ID, "7891000100103", "Arroz 5kg", "UN", new BigDecimal("9.90"), new BigDecimal("2"));
    sale.addItem(BEANS_ID, null, "Feijão 1kg", "UN", new BigDecimal("8.50"), BigDecimal.ONE);
    return sale;
  }
}
