package com.minimarket.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.sales.domain.SaleStatus;
import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link ListSalesUseCase}, sem Quarkus e sem banco: a porta é o dublê escrito à
 * mão. O que o caso de uso garante é a validação da paginação, o teto de {@code size} e a montagem
 * da página (total de itens e de páginas) — os filtros são repassados como chegaram e quem os
 * aplica é o adaptador do 803.
 */
class ListSalesUseCaseTest {

  private static final UUID CASH_SESSION_ID =
      UUID.fromString("0199a2b3-0000-7000-8000-000000000020");
  private static final UUID OPERATOR_ID = UUID.fromString("0199a2b3-0000-7000-8000-000000000001");
  private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-10-01T00:00:00Z");
  private static final Instant CREATED_AT = Instant.parse("2026-09-24T13:00:00Z");

  private final FakeSaleStore saleStore = new FakeSaleStore();

  private ListSalesUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new ListSalesUseCase();
    useCase.saleStore = saleStore;
  }

  @Test
  @DisplayName("repassa filtros e paginação à porta e monta a página com total de itens e páginas")
  void passesFiltersAndBuildsPage() {
    saleStore.searchResult = List.of(summary(1L), summary(2L));
    saleStore.countResult = 3L;

    SalePage page =
        useCase.execute(
            new ListSalesQuery(FROM, TO, SaleStatus.OPEN, CASH_SESSION_ID, OPERATOR_ID, 1, 2));

    assertThat(saleStore.searchCall)
        .as("a busca recebe os filtros e a página como o comando os trouxe")
        .isEqualTo(
            new FakeSaleStore.SearchCall(
                FROM, TO, SaleStatus.OPEN, CASH_SESSION_ID, OPERATOR_ID, 1, 2));
    assertThat(saleStore.countCall)
        .as("a contagem recebe os mesmos filtros, sem paginação")
        .isEqualTo(
            new FakeSaleStore.CountCall(FROM, TO, SaleStatus.OPEN, CASH_SESSION_ID, OPERATOR_ID));
    assertThat(page.items()).hasSize(2);
    assertThat(page.page()).isEqualTo(1);
    assertThat(page.size()).isEqualTo(2);
    assertThat(page.totalItems()).isEqualTo(3);
    assertThat(page.totalPages()).isEqualTo(2);
  }

  @Test
  @DisplayName("sem filtro: nulos passam como estão e página vazia tem zero páginas")
  void passesNullFilters() {
    SalePage page = useCase.execute(new ListSalesQuery(null, null, null, null, null, 0, 20));

    assertThat(saleStore.searchCall)
        .isEqualTo(new FakeSaleStore.SearchCall(null, null, null, null, null, 0, 20));
    assertThat(saleStore.countCall)
        .isEqualTo(new FakeSaleStore.CountCall(null, null, null, null, null));
    assertThat(page.items()).isEmpty();
    assertThat(page.totalItems()).isZero();
    assertThat(page.totalPages()).isZero();
  }

  @Test
  @DisplayName("size acima de 100 é limitado ao teto, não recusado")
  void limitsSizeToCeiling() {
    SalePage page = useCase.execute(new ListSalesQuery(null, null, null, null, null, 0, 250));

    assertThat(saleStore.searchCall.size()).as("o teto do §9.1 vale na consulta").isEqualTo(100);
    assertThat(page.size()).isEqualTo(100);
  }

  @Test
  @DisplayName("page negativo ou size menor que 1: 400 VALIDATION_ERROR sem consultar a porta")
  void rejectsInvalidPagination() {
    assertValidationError(
        () -> useCase.execute(new ListSalesQuery(null, null, null, null, null, -1, 20)),
        "page deve ser maior ou igual a 0");
    assertValidationError(
        () -> useCase.execute(new ListSalesQuery(null, null, null, null, null, 0, 0)),
        "size deve ser maior ou igual a 1");

    assertThat(saleStore.searchCall).as("a recusa acontece antes da porta").isNull();
    assertThat(saleStore.countCall).isNull();
  }

  /** Paginação inválida é forma do comando: 400 {@code VALIDATION_ERROR}. */
  private static void assertValidationError(ThrowingCallable call, String messageFragment) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
              assertThat(error.getMessage()).contains(messageFragment);
            });
  }

  /** Linha do histórico com o mínimo que a página transporta. */
  private static SaleSummary summary(long number) {
    return new SaleSummary(
        UUID.randomUUID(),
        UUID.fromString("0199a2b3-0000-7000-8000-000000000002"),
        number,
        SaleStatus.OPEN,
        CASH_SESSION_ID,
        UUID.fromString("0199a2b3-0000-7000-8000-000000000010"),
        OPERATOR_ID,
        null,
        new BigDecimal("19.80"),
        new BigDecimal("0.00"),
        new BigDecimal("19.80"),
        new BigDecimal("0.00"),
        new BigDecimal("0.00"),
        1,
        CREATED_AT,
        null);
  }
}
