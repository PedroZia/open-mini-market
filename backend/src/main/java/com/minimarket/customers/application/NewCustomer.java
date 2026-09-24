package com.minimarket.customers.application;

import java.util.UUID;

/**
 * Dados que a porta {@link CustomerStore} precisa para inserir um cliente novo. O {@code storeId}
 * chega já resolvido pelo caso de uso (loja única do MVP, §5.3) e o cliente nasce ativo, como no
 * default da tabela. {@code taxId} (CPF já normalizado pelo {@link TaxIdValidator}), {@code phone},
 * {@code email} e {@code notes} são opcionais (nulos).
 */
public record NewCustomer(
    UUID storeId, String name, String taxId, String phone, String email, String notes) {}
