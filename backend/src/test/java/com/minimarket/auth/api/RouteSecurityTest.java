package com.minimarket.auth.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Nenhuma rota de {@code /api/v1} esquecida sem autenticação (passo 308). O teste enumera as rotas
 * registradas no app em runtime e confere duas coisas:
 *
 * <ol>
 *   <li>a enumeração bate com a lista explícita {@link #API_ROUTES} — rota nova, renomeada ou
 *       removida sem atualizar a lista quebra o build (e a lista obriga a pensar no 401 da rota
 *       nova);
 *   <li>cada rota fora das exceções públicas {@link #PUBLIC_ROUTES} responde, <em>sem token</em>,
 *       401 {@code problem+json} {@code INVALID_CREDENTIALS} — o 401 do challenge do bearer, não o
 *       corpo de um recurso que rodou sem autenticação.
 * </ol>
 *
 * <p><strong>Como a enumeração funciona.</strong> Os beans CDI cuja classe é anotada com
 * {@code @Path} são os resources JAX-RS (o Quarkus registra todo resource como bean
 * {@code @Singleton} — comportamento do ArC, verificado na prática). De cada método público se lê o
 * verbo HTTP pela anotação meta-anotada com {@code @HttpMethod} ({@code @GET}, {@code @POST}, ...,
 * inclusive uma anotação própria do app) e se soma o {@code @Path} da classe ao do método. A API é
 * a documentada do CDI: {@code BeanManager} é injetável (spec CDI 4.1, §11.1.3) e {@code
 * getBeans(Type, Annotation...)} devolve os beans que casam com o tipo e os qualificadores
 * (§11.1.4) — com {@code @Any} (o qualificador de todo bean) e {@code Object.class} vêm todos; o
 * Quarkus documenta o suporte a {@code BeanManager} e aos métodos de {@code BeanContainer} na <a
 * href="https://quarkus.io/version/3.33/guides/cdi-reference">referência de CDI</a>. O {@code
 * Router} do Vert.x foi testado e descartado: o REST anota as rotas por regex e {@code
 * Route.getPath()} devolve nulo para quase todas.
 *
 * <p><strong>Limites.</strong> A enumeração só enxerga rotas de resources JAX-RS que são beans CDI,
 * e só as de {@code /api/v1} (o prefixo da API, §9.1): os erros do {@link
 * com.minimarket.shared.api.ErrorTestResource} em {@code /test/errors} ficam fora do contrato, os
 * {@code /q/health} também (o SmallRye Health não é resource JAX-RS) e sub-resource locators
 * (método sem verbo, que devolve o recurso filho) não são rota e não aparecem. Este teste prova
 * <em>autenticação</em>; qual permissão cada rota exige é do {@code PermissionMatrixTest}.
 *
 * <p><strong>Como conferir que ele quebra de propósito.</strong> Tire uma rota da lista (por
 * exemplo o {@code new Route("POST", "/api/v1/auth/logout")}) ou renomeie o {@code @Path} de um
 * método de {@link AuthResource}: {@link #enumeratesEveryApiRoute()} falha apontando a rota que
 * ficou fora. Para ver o outro lado, libere uma rota na política ({@code
 * quarkus.http.auth.permission.public.paths}) sem pôr em {@link #PUBLIC_ROUTES}: {@link
 * #rejectsAnonymousAccess()} falha na rota que deixou de exigir token.
 *
 * <p>Todas as requisições daqui são anônimas e nenhuma grava no banco: as exceções públicas chegam
 * ao recurso com corpo inválido ou em rota de leitura. Por isso a classe não estende {@link
 * com.minimarket.IntegrationTestBase} nem precisa de limpeza no fim.
 */
@QuarkusTest
class RouteSecurityTest {

  /** Prefixo da API (§9.1): o que não começa assim não é rota do contrato desta fase. */
  private static final String API_PREFIX = "/api/v1";

  /**
   * Piso de rotas da API conhecidas: pega enumeração vazia/quebrada antes da conferência da lista.
   */
  private static final int MINIMUM_API_ROUTES = 21;

  private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([^}]+)}");

  /** Ordem estável das rotas (caminho e depois verbo): a falha e as requisições não variam. */
  private static final Comparator<Route> BY_PATH_THEN_METHOD =
      Comparator.comparing(Route::path).thenComparing(Route::method);

  private static final Route LOGIN_ROUTE = new Route("POST", "/api/v1/auth/login");

  private static final Route META_ROUTE = new Route("GET", "/api/v1/meta");

  /**
   * As duas exceções públicas de {@code /api/v1}, com a justificativa de cada uma. Elas existem
   * para provar que chegam ao recurso — o login é o caminho pelo qual o cliente obtém o token e o
   * {@code /meta} abre o app antes de autenticar (§9.3).
   */
  private static final Set<Route> PUBLIC_ROUTES = Set.of(LOGIN_ROUTE, META_ROUTE);

  /**
   * Lista explícita das rotas de {@code /api/v1}: é o contrato do teste. Os resources de teste de
   * {@code src/test} entram na lista como qualquer rota — em {@code %test} eles são o app, e
   * excluí-los por nome de classe deixaria um ponto cego para o próximo resource de teste sob a
   * API.
   */
  private static final Set<Route> API_ROUTES =
      Set.of(
          LOGIN_ROUTE,
          META_ROUTE,
          new Route("GET", "/api/v1/auth/me"),
          new Route("POST", "/api/v1/auth/logout"),
          new Route("GET", "/api/v1/auth/sessions"),
          new Route("DELETE", "/api/v1/auth/sessions/{id}"),
          new Route("POST", "/api/v1/auth/password"),
          new Route("GET", "/api/v1/users"),
          new Route("POST", "/api/v1/users"),
          new Route("GET", "/api/v1/users/{id}"),
          new Route("PUT", "/api/v1/users/{id}"),
          new Route("POST", "/api/v1/users/{id}/disable"),
          new Route("POST", "/api/v1/users/{id}/enable"),
          new Route("POST", "/api/v1/users/{id}/password-reset"),
          new Route("DELETE", "/api/v1/users/{id}/sessions"),
          new Route("GET", "/api/v1/roles"),
          new Route("PUT", "/api/v1/roles/{code}/permissions"),
          new Route("GET", "/api/v1/categories"),
          new Route("POST", "/api/v1/categories"),
          new Route("PUT", "/api/v1/categories/{id}"),
          new Route("DELETE", "/api/v1/categories/{id}"),
          new Route("GET", "/api/v1/products"),
          new Route("POST", "/api/v1/products"),
          // Resources só de teste (passos 206, 302, 306): protegidos pela política global como
          // qualquer rota da API em %test.
          new Route("GET", "/api/v1/test/identity"),
          new Route("GET", "/api/v1/test/operation-context"),
          new Route("GET", "/api/v1/test/require-permission"),
          new Route("GET", "/api/v1/test/require-permission/audit"));

  /** Rotas chamadas sem token, em ordem estável para a falha e as requisições não variarem. */
  private static final List<Route> PRIVATE_ROUTES =
      API_ROUTES.stream()
          .filter(route -> !PUBLIC_ROUTES.contains(route))
          .sorted(BY_PATH_THEN_METHOD)
          .toList();

  @Inject BeanManager beanManager;

  @Test
  @DisplayName("a enumeração das rotas registradas bate com a lista explícita de /api/v1")
  void enumeratesEveryApiRoute() {
    Set<Route> enumerated = enumerateApiRoutes();

    // Sanidade primeiro: um enumerador que devolvesse pouco (ou nada) faria a conferência abaixo
    // passar em silêncio se a lista encolhesse junto, então rotas conhecidas e um piso fixam o
    // mínimo.
    assertThat(enumerated)
        .as("enumeração suspeita: rotas conhecidas de /api/v1 não foram encontradas")
        .hasSizeGreaterThanOrEqualTo(MINIMUM_API_ROUTES)
        .contains(
            new Route("GET", "/api/v1/users"),
            new Route("GET", "/api/v1/roles"),
            new Route("POST", "/api/v1/users"),
            new Route("POST", "/api/v1/auth/logout"));

    assertThat(API_ROUTES).as("exceção pública fora da lista de rotas").containsAll(PUBLIC_ROUTES);

    assertThat(enumerated)
        .as("rota nova, renomeada ou removida de /api/v1: atualize API_ROUTES e cubra o 401 dela")
        .containsExactlyInAnyOrderElementsOf(API_ROUTES);
  }

  @Test
  @DisplayName("sem token, cada rota de /api/v1 responde 401 problem+json INVALID_CREDENTIALS")
  void rejectsAnonymousAccess() {
    for (Route route : PRIVATE_ROUTES) {
      // Uma vez por rota: o `instance` do problem precisa apontar para o mesmo caminho chamado.
      String path = concretePath(route.path());

      Response response = given().when().request(route.method(), path).then().extract().response();

      assertThat(response.statusCode())
          .as("%s %s sem token precisa exigir autenticação", route.method(), path)
          .isEqualTo(401);
      assertThat(response.contentType())
          .as("%s %s: o 401 precisa ser problem+json", route.method(), path)
          .contains("application/problem+json");
      assertThat(response.jsonPath().getString("code"))
          .as("%s %s: código do 401", route.method(), path)
          .isEqualTo("INVALID_CREDENTIALS");
      assertThat(response.jsonPath().getString("instance"))
          .as("%s %s: a resposta é do caminho pedido", route.method(), path)
          .isEqualTo(path);
      assertThat(response.jsonPath().getString("traceId"))
          .as("%s %s: correlação da resposta", route.method(), path)
          .isNotBlank();
    }
  }

  @Test
  @DisplayName("as exceções públicas chegam ao recurso: /meta responde 200 e login inválido 400")
  void publicRoutesReachTheResource() {
    // 200 prova que a requisição anônima passou pela autorização e chegou ao recurso.
    Response meta =
        given().when().request(META_ROUTE.method(), META_ROUTE.path()).then().extract().response();

    assertThat(meta.statusCode()).as("GET /api/v1/meta é público").isEqualTo(200);
    assertThat(meta.contentType()).contains("application/json");
    assertThat(meta.jsonPath().getString("apiVersion")).isEqualTo("v1");

    // 400 de validação (e não o 401 do challenge) prova que o login é público e chega ao recurso.
    Response login =
        given()
            .contentType("application/json")
            .body("{}")
            .when()
            .request(LOGIN_ROUTE.method(), LOGIN_ROUTE.path())
            .then()
            .extract()
            .response();

    assertThat(login.statusCode()).as("POST /api/v1/auth/login é público").isEqualTo(400);
    assertThat(login.contentType()).contains("application/problem+json");
    assertThat(login.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
  }

  /**
   * Rotas de {@code /api/v1} registradas no app: para cada bean CDI anotado com {@code @Path}, soma
   * o verbo de cada método público ao path da classe e ao do método.
   */
  private Set<Route> enumerateApiRoutes() {
    Set<Route> routes = new TreeSet<>(BY_PATH_THEN_METHOD);
    for (Bean<?> bean : beanManager.getBeans(Object.class, Any.Literal.INSTANCE)) {
      Class<?> resource = bean.getBeanClass();
      Path classPath = resource.getAnnotation(Path.class);
      if (classPath == null) {
        continue;
      }
      for (Method method : resource.getMethods()) {
        String verb = httpMethodOf(method);
        if (verb == null) {
          continue;
        }
        Path methodPath = method.getAnnotation(Path.class);
        String path = joinPaths(classPath.value(), methodPath == null ? "" : methodPath.value());
        if (path.startsWith(API_PREFIX)) {
          routes.add(new Route(verb, path));
        }
      }
    }
    return routes;
  }

  /** Verbo HTTP declarado no método, ou nulo quando o método não é um método de recurso JAX-RS. */
  private static String httpMethodOf(Method method) {
    for (Annotation annotation : method.getAnnotations()) {
      HttpMethod httpMethod = annotation.annotationType().getAnnotation(HttpMethod.class);
      if (httpMethod != null) {
        return httpMethod.value();
      }
    }
    return null;
  }

  /** `@Path` da classe + `@Path` do método, sem barra dupla nem barra final sobrando. */
  private static String joinPaths(String classPath, String methodPath) {
    String joined = (classPath + "/" + methodPath).replaceAll("/{2,}", "/");
    return joined.length() > 1 && joined.endsWith("/")
        ? joined.substring(0, joined.length() - 1)
        : joined;
  }

  /**
   * Caminho chamável: cada `{param}` vira um valor sintético — UUID para `{id}`, texto para o
   * resto. O valor do parâmetro não importa: a política global barra a requisição antes do
   * roteamento.
   */
  private static String concretePath(String path) {
    return PATH_PARAMETER
        .matcher(path)
        .replaceAll(
            match -> "{id}".equals(match.group()) ? UUID.randomUUID().toString() : "synthetic");
  }

  /** Rota do teste: verbo e caminho como estão na anotação, com os `{param}` preservados. */
  private record Route(String method, String path) {}
}
