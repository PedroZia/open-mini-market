package com.minimarket.users.application;

/**
 * Porta de hash de senha: a senha em texto puro nunca é persistida nem devolvida. O adaptador
 * Argon2id fica em {@code users.infrastructure}.
 */
public interface PasswordHasher {

  /** Gera o hash da senha com salt aleatório, no formato PHC. */
  String hash(String rawPassword);

  /**
   * Confere a senha em texto puro contra o hash armazenado. Devolve {@code false} — nunca lança —
   * para hash nulo, vazio ou malformado, o que sustenta o fluxo de hash dummy do login.
   */
  boolean verify(String rawPassword, String passwordHash);

  /**
   * Indica se o hash precisa ser regerado: ausente, malformado, de outro algoritmo ou com
   * parâmetros (memória, iterações, paralelismo) mais fracos que a configuração atual.
   */
  boolean needsRehash(String passwordHash);
}
