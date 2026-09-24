package com.minimarket.customers.application;

/**
 * Corpo do cadastro de cliente. {@code name} é obrigatório; {@code taxId}, {@code phone}, {@code
 * email} e {@code notes} são opcionais (nulos). O caso de uso normaliza CPF e telefone — a string
 * crua do cliente nunca chega ao banco sem passar por lá.
 */
public record CreateCustomerCommand(
    String name, String taxId, String phone, String email, String notes) {}
