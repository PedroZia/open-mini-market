package com.minimarket.customers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.minimarket.shared.domain.BusinessException;
import com.minimarket.shared.domain.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unitários puros do {@link ListCustomersUseCase}, sem Quarkus e sem banco: a porta é um dublê à
 * mão que devolve uma massa fixa por página. O que o caso de uso decide — validar {@code
 * page}/{@code size}, limitar o teto de 100 e calcular {@code totalPages} — é o alvo aqui; o filtro
 * textual de verdade fica com o {@code CustomerRepositoryTest} (502a) e com o teste de API do 502b.
 */
class ListCustomersUseCaseTest {

  private static final UUID STORE_ID = UUID.randomUUID();
  private static final Instant CREATED_AT = Instant.parse("2026-01-02T03:04:05Z");

  private final FakeCustomerStore customerStore = new FakeCustomerStore();

  private ListCustomersUseCase useCase;

  @BeforeEach
  void setUp() {
    useCase = new ListCustomersUseCase();
    useCase.customerStore = customerStore;
  }

  @Test
  @DisplayName("devolve a página com o totalItems e o totalPages do filtro, sem paginação no count")
  void returnsPageWithTotals() {
    customerStore.totalItems = 25;
    customerStore.pageItems = customers("Ana", 10);

    CustomerPage page = useCase.execute("ana", 0, 10);

    assertThat(customerStore.searchCall)
        .as("o filtro e a página chegam à porta")
        .isEqualTo(new Search("ana", 0, 10));
    assertThat(customerStore.countCall).isEqualTo("ana");
    assertThat(page.items()).hasSize(10);
    assertThat(page.page()).isZero();
    assertThat(page.size()).isEqualTo(10);
    assertThat(page.totalItems()).isEqualTo(25);
    assertThat(page.totalPages()).as("25 itens em páginas de 10").isEqualTo(3);
  }

  @Test
  @DisplayName("size acima de 100 é limitado ao teto, como no §9.1")
  void clampsSizeToMaximum() {
    customerStore.totalItems = 1;

    CustomerPage page = useCase.execute(null, 0, 500);

    assertThat(page.size()).isEqualTo(100);
    assertThat(customerStore.searchCall.size()).isEqualTo(100);
    assertThat(page.totalPages()).as("1 item em página de 100").isEqualTo(1);
  }

  @Test
  @DisplayName("page negativo ou size menor que 1 é 400 sem consultar a porta")
  void rejectsInvalidPagination() {
    for (int[] invalid : List.of(new int[] {-1, 20}, new int[] {0, 0}, new int[] {0, -5})) {
      assertThatThrownBy(() -> useCase.execute(null, invalid[0], invalid[1]))
          .isInstanceOfSatisfying(
              BusinessException.class,
              error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    assertThat(customerStore.searchCall).as("parâmetro inválido barra antes da consulta").isNull();
    assertThat(customerStore.countCall).isNull();
  }

  private static List<CustomerSummary> customers(String base, int count) {
    return IntStream.range(1, count + 1)
        .mapToObj(
            index ->
                new CustomerSummary(
                    UUID.randomUUID(),
                    STORE_ID,
                    base + " %02d".formatted(index),
                    null,
                    null,
                    null,
                    null,
                    true,
                    CREATED_AT,
                    CREATED_AT,
                    null,
                    0))
        .toList();
  }

  /** Dublê de {@link CustomerStore}: devolve massa fixa por página e guarda o que foi pedido. */
  private static final class FakeCustomerStore implements CustomerStore {

    private List<CustomerSummary> pageItems = new ArrayList<>();
    private long totalItems;
    private Search searchCall;
    private String countCall;

    @Override
    public List<CustomerSummary> search(String search, int page, int size) {
      searchCall = new Search(search, page, size);
      return pageItems;
    }

    @Override
    public long count(String search) {
      countCall = search;
      return totalItems;
    }

    @Override
    public UUID insert(NewCustomer customer) {
      throw new UnsupportedOperationException("insert não é usado por ListCustomers");
    }

    @Override
    public Optional<CustomerSummary> findById(UUID id) {
      throw new UnsupportedOperationException("findById não é usado por ListCustomers");
    }

    @Override
    public Optional<CustomerSummary> update(
        UUID id, String name, String taxId, String phone, String email, String notes) {
      throw new UnsupportedOperationException("update não é usado por ListCustomers");
    }

    @Override
    public Optional<CustomerSummary> disable(UUID id) {
      throw new UnsupportedOperationException("disable não é usado por ListCustomers");
    }

    @Override
    public boolean existsActiveTaxId(String taxId) {
      throw new UnsupportedOperationException("existsActiveTaxId não é usado por ListCustomers");
    }
  }

  /** Chamada de busca que o caso de uso fez à porta. */
  private record Search(String search, int page, int size) {}
}
