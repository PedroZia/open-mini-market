package com.minimarket;

import jakarta.inject.Inject;
import javax.sql.DataSource;

/**
 * Base opcional dos testes de integração: expõe o {@link DataSource} para consultas diretas ao
 * banco e concentra o que for comum a esse tipo de teste.
 *
 * <p>A classe concreta deve declarar {@code @QuarkusTest} — o Quarkus registra o bean de teste pela
 * anotação declarada, não pela herdada — e o PostgreSQL real sobe via Dev Services (Docker precisa
 * estar rodando). Testes que não precisam do banco não estendem esta classe.
 */
public abstract class IntegrationTestBase {

  @Inject protected DataSource dataSource;
}
