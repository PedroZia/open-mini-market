package com.minimarket.auth.domain;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Gera o token opaco de sessão conforme o §6.2 do plano: 32 bytes de {@link SecureRandom} (256 bits
 * de entropia) codificados em Base64URL sem padding — 43 caracteres.
 *
 * <p>Sem estado e sem CDI: quem precisa de um token instancia e chama {@link #generate()}.
 */
public final class TokenGenerator {

  /** Entropia do token em bytes. */
  private static final int TOKEN_BYTES = 32;

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

  private final SecureRandom secureRandom = new SecureRandom();

  /** Devolve um token novo e imprevisível, distinto a cada chamada. */
  public String generate() {
    byte[] token = new byte[TOKEN_BYTES];
    secureRandom.nextBytes(token);
    return ENCODER.encodeToString(token);
  }
}
