package com.minimarket;

import static io.restassured.RestAssured.given;

import com.minimarket.support.TestAdmin;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;

/**
 * Base opcional dos testes de integração: expõe o {@link DataSource} para consultas diretas ao
 * banco, o ADMIN real da suíte (passo 307a) e concentra o que for comum a esse tipo de teste.
 *
 * <p>A classe concreta deve declarar {@code @QuarkusTest} — o Quarkus registra o bean de teste pela
 * anotação declarada, não pela herdada — e o PostgreSQL real sobe via Dev Services (Docker precisa
 * estar rodando). Testes que não precisam do banco não estendem esta classe.
 */
public abstract class IntegrationTestBase {

  @Inject protected DataSource dataSource;

  /** ADMIN da fixture (passo 307a): as rotas de `/api/v1` exigem token desde a política global. */
  @Inject protected TestAdmin testAdmin;

  /**
   * Token do ADMIN da fixture, criado no primeiro uso. O campo é da instância do teste — o JUnit
   * cria uma por método — então cada teste faz o próprio login, sem token de um vazando para o
   * outro.
   */
  private String adminToken;

  /** Token de um ADMIN real, para os testes que exercitam rotas protegidas por permissão. */
  protected String adminToken() {
    if (adminToken == null) {
      adminToken = testAdmin.login();
    }
    return adminToken;
  }

  /** Requisição autenticada como o ADMIN da fixture: o atalho de quem exercita rota protegida. */
  protected RequestSpecification asAdmin() {
    return given().header("Authorization", "Bearer " + adminToken());
  }

  /**
   * O ADMIN da fixture vive só durante o teste: some no fim de cada um para os testes de
   * repositório (como o {@code UserRepositoryTest.searches()}, que usa {@code containsExactly})
   * encontrarem o banco como o deixaram. Roda depois dos {@code @AfterEach} da subclasse, que é
   * quem restaura o que o teste mutou.
   */
  @AfterEach
  void removeTestAdmin() throws SQLException {
    testAdmin.remove();
  }
}
