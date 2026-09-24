package com.minimarket.sales.application;

import com.minimarket.customers.application.CustomerStore;
import com.minimarket.customers.application.CustomerSummary;
import com.minimarket.customers.application.NewCustomer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Dublê de {@link CustomerStore} dos unitários do vínculo de cliente (passo 811): guarda o cliente
 * do cenário e responde só o {@code findAnyById}, que é o que o caso de uso usa. O resto da porta é
 * exercitado pelos testes de integração do 502a.
 */
final class FakeCustomerStore implements CustomerStore {

  /** Cliente do cenário; nulo é o cenário do cliente inexistente. */
  CustomerSummary customer;

  @Override
  public Optional<CustomerSummary> findAnyById(UUID id) {
    return Optional.ofNullable(customer).filter(found -> found.id().equals(id));
  }

  @Override
  public UUID insert(NewCustomer customer) {
    throw new UnsupportedOperationException("insert não é usado pelo vínculo de cliente");
  }

  @Override
  public Optional<CustomerSummary> findById(UUID id) {
    throw new UnsupportedOperationException(
        "findById não é usado pelo vínculo: o desativado precisa aparecer (findAnyById)");
  }

  @Override
  public List<CustomerSummary> search(String search, int page, int size) {
    throw new UnsupportedOperationException("search não é usado pelo vínculo de cliente");
  }

  @Override
  public long count(String search) {
    throw new UnsupportedOperationException("count não é usado pelo vínculo de cliente");
  }

  @Override
  public Optional<CustomerSummary> update(
      UUID id, String name, String taxId, String phone, String email, String notes) {
    throw new UnsupportedOperationException("update não é usado pelo vínculo de cliente");
  }

  @Override
  public Optional<CustomerSummary> disable(UUID id) {
    throw new UnsupportedOperationException("disable não é usado pelo vínculo de cliente");
  }

  @Override
  public boolean existsActiveTaxId(String taxId) {
    throw new UnsupportedOperationException(
        "existsActiveTaxId não é usado pelo vínculo de cliente");
  }
}
