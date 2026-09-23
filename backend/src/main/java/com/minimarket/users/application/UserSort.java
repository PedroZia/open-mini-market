package com.minimarket.users.application;

/**
 * Campos ordenáveis da listagem de usuários (§9.1). Whitelist fechada: o adaptador traduz o enum em
 * coluna JPQL, então nenhuma string do cliente chega à consulta.
 */
public enum UserSort {
  USERNAME,
  DISPLAY_NAME,
  CREATED_AT
}
