package com.minimarket.users.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.minimarket.users.application.PasswordHasher;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prova a fiação da configuração: a porta injetada produz Argon2id no formato PHC com os parâmetros
 * de {@code application.properties}.
 */
@QuarkusTest
class Argon2PasswordHasherConfigTest {

  @Inject PasswordHasher passwordHasher;

  @Test
  @DisplayName("hash sai no formato PHC com m=19456,t=2,p=1 da configuração")
  void hashesWithConfiguredParameters() {
    String hash = passwordHasher.hash("senha-secreta-123");

    assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
    assertThat(passwordHasher.verify("senha-secreta-123", hash)).isTrue();
    assertThat(passwordHasher.needsRehash(hash)).isFalse();
  }
}
