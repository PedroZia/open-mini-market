package com.minimarket.users.infrastructure;

import com.minimarket.users.application.PasswordHasher;
import com.password4j.Argon2Function;
import com.password4j.Password;
import com.password4j.types.Argon2;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Adaptador Argon2id da porta {@link PasswordHasher}; os parâmetros vêm da configuração. */
@ApplicationScoped
public class Argon2PasswordHasher implements PasswordHasher {

  private final int saltLength;
  private final Argon2Function function;

  public Argon2PasswordHasher(
      @ConfigProperty(name = "minimarket.security.password.memory-kib") int memoryKib,
      @ConfigProperty(name = "minimarket.security.password.iterations") int iterations,
      @ConfigProperty(name = "minimarket.security.password.parallelism") int parallelism,
      @ConfigProperty(name = "minimarket.security.password.salt-length") int saltLength,
      @ConfigProperty(name = "minimarket.security.password.hash-length") int hashLength) {
    this.saltLength = saltLength;
    this.function =
        Argon2Function.getInstance(memoryKib, iterations, parallelism, hashLength, Argon2.ID);
  }

  @Override
  public String hash(String rawPassword) {
    return Password.hash(rawPassword).addRandomSalt(saltLength).with(function).getResult();
  }

  @Override
  public boolean verify(String rawPassword, String passwordHash) {
    if (rawPassword == null
        || rawPassword.isEmpty()
        || passwordHash == null
        || passwordHash.isBlank()) {
      return false;
    }
    try {
      // A verificação usa os parâmetros gravados no próprio hash: o PHC inteiro é comparado, então
      // conferir com a configuração atual rejeitaria um hash legítimo gerado com parâmetros
      // antigos.
      return Argon2Function.getInstanceFromHash(passwordHash).check(rawPassword, passwordHash);
    } catch (RuntimeException e) {
      // Hash malformado/truncado não é senha errada: devolve false em vez de estourar no login.
      return false;
    }
  }

  @Override
  public boolean needsRehash(String passwordHash) {
    if (passwordHash == null || passwordHash.isBlank()) {
      return true;
    }
    Argon2Function stored;
    try {
      stored = Argon2Function.getInstanceFromHash(passwordHash);
    } catch (RuntimeException e) {
      return true;
    }
    return stored.getVariant() != Argon2.ID
        || stored.getMemory() < function.getMemory()
        || stored.getIterations() < function.getIterations()
        || stored.getParallelism() < function.getParallelism();
  }
}
