package com.minimarket.users.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.password4j.Argon2Function;
import com.password4j.Password;
import com.password4j.types.Argon2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unitários puros do adaptador Argon2id, sem subir o Quarkus. */
class Argon2PasswordHasherTest {

  private static final String PASSWORD = "senha-secreta-123";

  /** Hash fixo de outra senha, como o usado no login de usuário inexistente (passo 204). */
  private static final String DUMMY_HASH =
      "$argon2id$v=19$m=19456,t=2,p=1$bDyECbVjAgIXAWe2DWRm4g$sIFf/1JHYBDn/nIFX+Q1N4Z0dn5oksBgd+y/f58DUd8";

  private Argon2PasswordHasher hasher;

  @BeforeEach
  void setUp() {
    hasher = new Argon2PasswordHasher(19456, 2, 1, 16, 32);
  }

  @Test
  @DisplayName("hash sai no formato PHC e não contém a senha em texto puro")
  void hashIsNotThePassword() {
    String hash = hasher.hash(PASSWORD);

    assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$").isNotEqualTo(PASSWORD);
    assertThat(hash).doesNotContain(PASSWORD);
  }

  @Test
  @DisplayName("verify aceita a senha correta e rejeita a senha errada")
  void verifyAcceptsOnlyTheRightPassword() {
    String hash = hasher.hash(PASSWORD);

    assertThat(hasher.verify(PASSWORD, hash)).isTrue();
    assertThat(hasher.verify("outra-senha", hash)).isFalse();
  }

  @Test
  @DisplayName("dois hashes da mesma senha diferem pelo salt e ambos verificam")
  void hashesDifferBySalt() {
    String first = hasher.hash(PASSWORD);
    String second = hasher.hash(PASSWORD);

    assertThat(first).isNotEqualTo(second);
    assertThat(hasher.verify(PASSWORD, first)).isTrue();
    assertThat(hasher.verify(PASSWORD, second)).isTrue();
  }

  @Test
  @DisplayName("needsRehash detecta parâmetros antigos e aceita os parâmetros atuais ou melhores")
  void needsRehashDetectsWeakerParameters() {
    Argon2Function legacy = Argon2Function.getInstance(8192, 1, 1, 32, Argon2.ID);
    Argon2Function stronger = Argon2Function.getInstance(32768, 3, 1, 32, Argon2.ID);
    Argon2Function argon2i = Argon2Function.getInstance(19456, 2, 1, 32, Argon2.I);
    String legacyHash = Password.hash(PASSWORD).addRandomSalt(16).with(legacy).getResult();

    assertThat(hasher.verify(PASSWORD, legacyHash)).isTrue();
    assertThat(hasher.needsRehash(legacyHash)).isTrue();
    assertThat(hasher.needsRehash(hasher.hash(PASSWORD))).isFalse();
    assertThat(
            hasher.needsRehash(
                Password.hash(PASSWORD).addRandomSalt(16).with(stronger).getResult()))
        .isFalse();
    assertThat(
            hasher.needsRehash(Password.hash(PASSWORD).addRandomSalt(16).with(argon2i).getResult()))
        .isTrue();
  }

  @Test
  @DisplayName("needsRehash é true para hash ausente, de outro algoritmo ou malformado")
  void needsRehashIsTrueForUnknownHashes() {
    assertThat(hasher.needsRehash(null)).isTrue();
    assertThat(hasher.needsRehash("")).isTrue();
    assertThat(hasher.needsRehash("   ")).isTrue();
    assertThat(hasher.needsRehash("$2a$10$abcdefghijklmnopqrstuv")).isTrue();
    assertThat(hasher.needsRehash("$scrypt$ln=16,r=8,p=1$c2FsdA$YWJj")).isTrue();
    assertThat(hasher.needsRehash("$argon2id$v=19$m=abc,t=2,p=1$c2FsdA$YWJj")).isTrue();
  }

  @Test
  @DisplayName("verificação contra hash dummy de usuário inexistente devolve false")
  void verifiesAgainstDummyHashOfUnknownUser() {
    assertThat(hasher.verify("qualquer-senha", DUMMY_HASH)).isFalse();
    assertThat(hasher.needsRehash(DUMMY_HASH)).isFalse();
  }

  @Test
  @DisplayName("verify devolve false, sem lançar, para hash malformado ou truncado")
  void verifyReturnsFalseForMalformedHash() {
    String valid = hasher.hash(PASSWORD);
    String[] malformed = {
      "nao-e-hash",
      "$argon2id$v=19$m=19456,t=2,p=1$c2FsdA",
      "$argon2id$v=19$m=19456,t=2$c2FsdA$YWJj",
      "$argon2id$v=19$m=abc,t=2,p=1$c2FsdA$YWJj",
      "$argon2id$v=19$m=19456,t=2,p=1$!!!$!!!",
      "$2a$10$abcdefghijklmnopqrstuv",
      "$scrypt$ln=16,r=8,p=1$c2FsdA$YWJj",
      valid.substring(0, valid.length() - 4)
    };

    assertThat(hasher.verify(null, valid)).isFalse();
    assertThat(hasher.verify(PASSWORD, null)).isFalse();
    assertThat(hasher.verify("", valid)).isFalse();
    assertThat(hasher.verify(PASSWORD, "   ")).isFalse();
    for (String hash : malformed) {
      assertThat(hasher.verify(PASSWORD, hash)).as("hash malformado: %s", hash).isFalse();
    }
  }
}
