package com.minimarket.customers.application;

import com.minimarket.shared.domain.ErrorCode;
import com.minimarket.shared.domain.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/**
 * Detalhe do cliente (§9.3 do plano): lê pela porta {@link CustomerStore} e traduz em 404 com o
 * código estável {@code CUSTOMER_NOT_FOUND} tanto o id desconhecido quanto o cliente desativado — o
 * soft delete do 502b tira o cliente da porta no mesmo movimento em que libera o CPF, então não há
 * filtro a repetir aqui. Leitura pura, sem {@code @Transactional}, como o {@code GetProductUseCase}
 * e o {@link ListCustomersUseCase}.
 */
@ApplicationScoped
public class GetCustomerUseCase {

  @Inject CustomerStore customerStore;

  public CustomerSummary execute(UUID id) {
    return customerStore.findById(id).orElseThrow(() -> notFound(id));
  }

  private static NotFoundException notFound(UUID id) {
    return new NotFoundException(
        ErrorCode.CUSTOMER_NOT_FOUND, "cliente %s não encontrado".formatted(id));
  }
}
