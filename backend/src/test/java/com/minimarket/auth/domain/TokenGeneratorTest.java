package com.minimarket.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unitários puros do gerador de token, sem subir o Quarkus. */
class TokenGeneratorTest {

  /**
   * Alfabeto Base64URL: letras, dígitos, {@code -} e {@code _}; sem {@code +}, {@code /} nem {@code
   * =}.
   */
  private static final Pattern BASE64URL = Pattern.compile("^[A-Za-z0-9_-]{43}$");

  private static final int DISTINCT_TOKENS = 200;

  private final TokenGenerator generator = new TokenGenerator();

  @Test
  @DisplayName("token tem 43 caracteres Base64URL sem padding")
  void tokenIsBase64UrlWithoutPadding() {
    String token = generator.generate();

    assertThat(token).matches(BASE64URL).doesNotContain("=");
  }

  @Test
  @DisplayName("200 chamadas geram 200 tokens distintos")
  void tokensAreDistinctOnEveryCall() {
    Set<String> tokens = new HashSet<>();
    for (int i = 0; i < DISTINCT_TOKENS; i++) {
      tokens.add(generator.generate());
    }

    assertThat(tokens).hasSize(DISTINCT_TOKENS);
  }
}
