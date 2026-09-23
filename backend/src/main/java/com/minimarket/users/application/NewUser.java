package com.minimarket.users.application;

/**
 * Dados que a porta {@link UserStore} precisa para inserir um usuário novo. Record de fronteira:
 * carrega o hash (nunca a senha em texto puro) e não deixa a entidade JPA atravessar para {@code
 * application}.
 */
public record NewUser(String username, String displayName, String passwordHash, String status) {}
