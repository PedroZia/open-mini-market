package com.minimarket.customers.application;

import java.util.UUID;

/**
 * Corpo do PUT de cliente: os cinco campos editáveis, com o mesmo significado do cadastro — o PUT
 * substitui o conjunto inteiro, então campo omitido vira nulo. O {@code id} vem do path e o {@code
 * version} do lock otimista é conferido pelo banco (o PUT não tem {@code If-Match}).
 */
public record UpdateCustomerCommand(
    UUID id, String name, String taxId, String phone, String email, String notes) {}
