package com.minimarket.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unitários puros do hash de token, sem subir o Quarkus. */
class TokenHasherTest {

  private static final String TOKEN = "token-opaco-de-teste-1234567890";

  private final TokenHasher hasher = new TokenHasher();

  @Test
  @DisplayName("hash é SHA-256 em hex minúsculo de 64 caracteres")
  void hashIsLowercaseHexSha256() {
    assertThat(hasher.hash("abc"))
        .hasSize(64)
        .matches("[0-9a-f]{64}")
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  @DisplayName("hash é estável: o mesmo token produz sempre o mesmo hash")
  void hashIsStable() {
    assertThat(hasher.hash(TOKEN)).isEqualTo(hasher.hash(TOKEN));
  }

  @Test
  @DisplayName("hash não guarda o token em claro")
  void hashDoesNotContainTheToken() {
    assertThat(hasher.hash(TOKEN)).doesNotContain(TOKEN);
  }

  @Test
  @DisplayName("hash rejeita token nulo")
  void hashRejectsNullToken() {
    assertThatIllegalArgumentException().isThrownBy(() -> hasher.hash(null));
  }

  @Test
  @DisplayName("matches aceita o token correto e rejeita o alterado em um caractere")
  void matchesAcceptsOnlyTheOriginalToken() {
    String tokenHash = hasher.hash(TOKEN);
    String altered = (TOKEN.charAt(0) == 'a' ? 'b' : 'a') + TOKEN.substring(1);

    assertThat(hasher.matches(TOKEN, tokenHash)).isTrue();
    assertThat(altered).isNotEqualTo(TOKEN);
    assertThat(hasher.matches(altered, tokenHash)).isFalse();
  }

  @Test
  @DisplayName("matches rejeita outro token de mesmo tamanho e hash de outro token")
  void matchesRejectsDifferentToken() {
    String tokenHash = hasher.hash(TOKEN);

    assertThat(hasher.matches("token-opaco-de-teste-1234567891", tokenHash)).isFalse();
    assertThat(hasher.matches(TOKEN, hasher.hash("outro-token"))).isFalse();
  }

  @Test
  @DisplayName("matches devolve false, sem lançar, para token ou hash nulo/em branco")
  void matchesReturnsFalseForNullOrBlank() {
    String tokenHash = hasher.hash(TOKEN);

    assertThat(hasher.matches(null, tokenHash)).isFalse();
    assertThat(hasher.matches("", tokenHash)).isFalse();
    assertThat(hasher.matches("   ", tokenHash)).isFalse();
    assertThat(hasher.matches(TOKEN, null)).isFalse();
    assertThat(hasher.matches(TOKEN, "")).isFalse();
    assertThat(hasher.matches(TOKEN, "   ")).isFalse();
  }

  @Test
  @DisplayName("matches devolve false, sem lançar, para hash malformado")
  void matchesReturnsFalseForMalformedHash() {
    String valid = hasher.hash(TOKEN);
    String[] malformed = {
      "nao-e-hash",
      "z".repeat(64),
      valid.substring(0, valid.length() - 1),
      valid + "0",
      valid.substring(0, 62) + "zz"
    };

    for (String tokenHash : malformed) {
      assertThat(hasher.matches(TOKEN, tokenHash)).as("hash malformado: %s", tokenHash).isFalse();
    }
  }
}
