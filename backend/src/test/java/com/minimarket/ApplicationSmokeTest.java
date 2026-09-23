package com.minimarket;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ApplicationSmokeTest {

  @Test
  @DisplayName("o contexto da aplicação sobe sem erro")
  void contextLoads() {}
}
