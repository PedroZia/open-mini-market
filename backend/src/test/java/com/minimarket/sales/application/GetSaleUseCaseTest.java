package com.minimarket.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.sales.domain.Sale;
import com.minimarket.sales.domain.SaleStatus;
import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.ForbiddenException;
import com.minimarket.shared.domain.NotFoundException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link GetSaleUseCase}, sem Quarkus e sem banco: a porta é o dublê escrito à
 * mão e o agregado é o de verdade. O cenário de permissão é o {@code canReadAny} do comando — quem
 * o resolve é a API, a partir do {@code AuthorizationService}.
 */
class GetSaleUseCaseTest {

  private static final UUID STORE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000002");
  private static final UUID SALE_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000030");
  private static final UUID CASH_SESSION_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000020");
  private static final UUID CASH_REGISTER_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000010");
  private static final UUID ANOTHER_REGISTER_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000011");
  private static final UUID OPERATOR_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000001");
  private static final Instant NOW = Instant.parse("2026-09-24T13:00:00Z");

  private final FakeSaleStore saleStore = new FakeSaleStore();

  private GetSaleUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new GetSaleUseCase();
    SaleAccessGuard saleAccessGuard = new SaleAccessGuard();
    saleAccessGuard.saleStore = saleStore;
    useCase.saleAccessGuard = saleAccessGuard;
    saleStore.sale = openSale();
  }

  @Test
  @DisplayName("dono lê a própria venda sem gravar nada")
  void readsOwnSale() {
    Sale sale = useCase.execute(new GetSaleCommand(SALE_ID, CASH_REGISTER_ID, false));

    assertThat(sale.id()).isEqualTo(SALE_ID);
    assertThat(saleStore.updateCount).as("leitura não grava").isZero();
  }

  @Test
  @DisplayName("venda de outro caixa sem report.read: 403 ACCESS_DENIED")
  void deniesSaleOfAnotherRegister() {
    assertThatThrownBy(
            () -> useCase.execute(new GetSaleCommand(SALE_ID, ANOTHER_REGISTER_ID, false)))
        .isInstanceOfSatisfying(
            ForbiddenException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.ACCESS_DENIED);
              assertThat(error.getMessage()).contains(SALE_ID.toString());
            });

    assertThat(saleStore.updateCount).isZero();
  }

  @Test
  @DisplayName("sessão sem caixa vinculado e sem report.read: 403 ACCESS_DENIED")
  void deniesSessionWithoutRegister() {
    assertThatThrownBy(() -> useCase.execute(new GetSaleCommand(SALE_ID, null, false)))
        .isInstanceOfSatisfying(
            ForbiddenException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.ACCESS_DENIED));
  }

  @Test
  @DisplayName("quem tem report.read lê a venda de outro caixa (bypass de gestão)")
  void allowsManagementBypass() {
    Sale sale = useCase.execute(new GetSaleCommand(SALE_ID, ANOTHER_REGISTER_ID, true));

    assertThat(sale.id()).isEqualTo(SALE_ID);
    assertThat(saleStore.updateCount).isZero();
  }

  @Test
  @DisplayName("venda inexistente: 404 SALE_NOT_FOUND mesmo para quem tem report.read")
  void rejectsUnknownSale() {
    saleStore.sale = null;

    for (boolean canReadAny : new boolean[] {false, true}) {
      assertThatThrownBy(
              () -> useCase.execute(new GetSaleCommand(SALE_ID, CASH_REGISTER_ID, canReadAny)))
          .isInstanceOfSatisfying(
              NotFoundException.class,
              error -> {
                assertThat(error.code()).isEqualTo(ErrorCode.SALE_NOT_FOUND);
                assertThat(error.getMessage()).contains(SALE_ID.toString());
              });
    }
  }

  @Test
  @DisplayName("venda concluída é consultável: a leitura não exige venda aberta")
  void readsCompletedSale() {
    saleStore.sale.complete(NOW);

    Sale sale = useCase.execute(new GetSaleCommand(SALE_ID, CASH_REGISTER_ID, false));

    assertThat(sale.status()).isEqualTo(SaleStatus.COMPLETED);
    assertThat(sale.completedAt()).isEqualTo(NOW);
  }

  /** Venda aberta e vazia, como o 805 a cria. */
  private static Sale openSale() {
    return new Sale(
        SALE_ID, STORE_ID, 7L, CASH_SESSION_ID, CASH_REGISTER_ID, OPERATOR_ID, null, NOW);
  }
}
