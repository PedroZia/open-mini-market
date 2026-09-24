package com.minimarket.cash.application;

import java.util.UUID;

/**
 * Projeção do caixa para a porta {@link CashRegisterStore}: sem entidade JPA atravessando para
 * {@code application}. Só os campos que a listagem de caixas (passo 602) usa — {@code storeId} e
 * {@code active} são detalhe do banco.
 */
public record CashRegisterSummary(UUID id, String code, String name) {}
