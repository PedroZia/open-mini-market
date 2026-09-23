package com.minimarket.users.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Normalização dos códigos de papel recebidos da API: trim, sem nulos, sem repetição e com a ordem
 * preservada. O código desconhecido passa por aqui intacto — quem recusa é o caso de uso.
 */
final class RoleCodes {

  private RoleCodes() {}

  static List<String> normalize(List<String> roleCodes) {
    if (roleCodes == null) {
      return List.of();
    }
    Set<String> unique = new LinkedHashSet<>();
    for (String code : roleCodes) {
      if (code != null) {
        unique.add(code.trim());
      }
    }
    return List.copyOf(unique);
  }
}
