package com.minimarket.auth.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hasheia o token de sessão com SHA-256 em hex minúsculo (64 caracteres) e compara em tempo
 * constante, conforme o §6.2 do plano: o banco guarda apenas o hash ({@code
 * auth_sessions.token_hash}), nunca o token em claro.
 *
 * <p>Sem estado e sem CDI: quem precisa do hash instancia e chama {@link #hash(String)} ou {@link
 * #matches(String, String)}.
 */
public final class TokenHasher {

  private static final String ALGORITHM = "SHA-256";
  private static final int SHA_256_HEX_LENGTH = 64;
  private static final HexFormat HEX = HexFormat.of();

  /**
   * Hash SHA-256 do token em hex minúsculo, sempre com 64 caracteres.
   *
   * @param token token em claro; não pode ser nulo
   * @throws IllegalArgumentException se o token for nulo
   */
  public String hash(String token) {
    if (token == null) {
      throw new IllegalArgumentException("token é obrigatório para o hash");
    }
    return HEX.formatHex(sha256(token));
  }

  /**
   * Compara o token em claro com o hash guardado usando {@link MessageDigest#isEqual(byte[],
   * byte[])}, que não vaza o tempo de comparação.
   *
   * <p>Nunca lança: token ou hash nulo/em branco e hash que não seja SHA-256 em hex devolvem {@code
   * false}.
   */
  public boolean matches(String token, String tokenHash) {
    if (token == null || token.isBlank() || tokenHash == null || tokenHash.isBlank()) {
      return false;
    }
    if (tokenHash.length() != SHA_256_HEX_LENGTH) {
      return false;
    }
    byte[] expected;
    try {
      expected = HEX.parseHex(tokenHash);
    } catch (IllegalArgumentException malformed) {
      return false;
    }
    return MessageDigest.isEqual(sha256(token), expected);
  }

  private static byte[] sha256(String token) {
    try {
      return MessageDigest.getInstance(ALGORITHM).digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 não disponível na JVM", e);
    }
  }
}
