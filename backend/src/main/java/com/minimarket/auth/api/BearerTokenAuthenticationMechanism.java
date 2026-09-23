package com.minimarket.auth.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minimarket.auth.application.AuthenticateSessionUseCase;
import com.minimarket.shared.api.ProblemDetail;
import com.minimarket.shared.api.RequestIdFilter;
import com.minimarket.shared.domain.ErrorCode;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.credential.TokenCredential;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.identity.request.TokenAuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import java.util.Set;
import java.util.UUID;

/**
 * Autenticação bearer das requisições (§6.2/§6.4, passo 206): lê {@code Authorization: Bearer
 * <token>} e delega a validação da sessão ao {@link BearerTokenIdentityProvider}, que roda o acesso
 * ao banco em worker thread pelo {@code runBlocking} do Quarkus Security.
 *
 * <p>Registrado como bean CDI, o mecanismo é chamado em toda requisição (autenticação proativa,
 * padrão do Quarkus): sem o header a requisição segue <em>anônima</em> — não é erro, e só vira 401
 * se a rota exigir autenticação. Header presente e token desconhecido/revogado/expirado vira 401
 * {@code problem+json}, escrito por {@link #sendChallenge}: o Quarkus só oferece status e headers
 * no challenge, então a resposta padrão da API é montada aqui.
 *
 * <p>O {@code X-Request-Id} também é ecoado nesta resposta: o filtro de correlação (passo 008) é do
 * JAX-RS e não chega a rodar quando a autenticação barra a requisição antes do REST.
 */
@ApplicationScoped
public class BearerTokenAuthenticationMechanism implements HttpAuthenticationMechanism {

  /** Esquema do header {@code Authorization} (RFC 6750). */
  public static final String BEARER_SCHEME = "Bearer";

  /**
   * Chave do {@code RoutingContext} e atributo da {@link AuthenticationFailedException} com o
   * {@link ErrorCode} que explica a falha: o provider registra o motivo, o challenge o lê para
   * escolher entre {@code INVALID_CREDENTIALS} e {@code SESSION_EXPIRED}.
   */
  public static final String FAILURE_CODE_ATTRIBUTE = "minimarket.auth.failure-code";

  private static final String PROBLEM_CONTENT_TYPE = "application/problem+json";

  @Inject ObjectMapper objectMapper;

  /**
   * Sem header {@code Authorization} não há identidade: devolve item nulo (anônimo). Com o header,
   * a validação é do {@code IdentityProviderManager}; a falha carrega o motivo para o challenge.
   */
  @Override
  public Uni<SecurityIdentity> authenticate(
      RoutingContext context, IdentityProviderManager identityProviderManager) {
    String token = bearerToken(context);
    if (token == null) {
      return Uni.createFrom().nullItem();
    }
    return identityProviderManager
        .authenticate(new TokenAuthenticationRequest(new TokenCredential(token, BEARER_SCHEME)))
        .onFailure(AuthenticationFailedException.class)
        .invoke(
            failure ->
                context.put(
                    FAILURE_CODE_ATTRIBUTE,
                    failureCode(failure.getAttribute(FAILURE_CODE_ATTRIBUTE))));
  }

  /**
   * Tipo de credencial que o mecanismo entrega ao {@code IdentityProviderManager}; é o que permite
   * o Quarkus validar no build que existe um {@code IdentityProvider} para ela.
   */
  @Override
  public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
    return Set.of(TokenAuthenticationRequest.class);
  }

  /**
   * Desafio padrão do bearer: 401 com {@code WWW-Authenticate}, corpo escrito por {@link
   * #sendChallenge}.
   */
  @Override
  public Uni<ChallengeData> getChallenge(RoutingContext context) {
    return Uni.createFrom()
        .item(
            new ChallengeData(
                ErrorCode.INVALID_CREDENTIALS.status(),
                HttpHeaders.WWW_AUTHENTICATE,
                BEARER_SCHEME));
  }

  /**
   * Escreve o 401 no formato da API ({@code problem+json}, §9.2) e encerra a resposta — devolver
   * {@code true} diz ao Quarkus que o challenge foi tratado e que não há mais nada a escrever.
   */
  @Override
  public Uni<Boolean> sendChallenge(RoutingContext context) {
    ErrorCode code = failureCode(context.get(FAILURE_CODE_ATTRIBUTE));
    String traceId = requestId(context);
    ProblemDetail problem =
        new ProblemDetail(
            code.type(),
            code.title(),
            code.status(),
            detailOf(code),
            context.request().path(),
            code.name(),
            traceId,
            null);
    context
        .response()
        .setStatusCode(code.status())
        .putHeader(HttpHeaders.CONTENT_TYPE, PROBLEM_CONTENT_TYPE)
        .putHeader(RequestIdFilter.REQUEST_ID_HEADER, traceId)
        .putHeader(HttpHeaders.WWW_AUTHENTICATE, BEARER_SCHEME)
        .end(json(problem));
    return Uni.createFrom().item(true);
  }

  /**
   * Token do header {@code Authorization}, ou nulo quando o header não é um bearer com token — sem
   * credencial a requisição segue anônima.
   */
  private static String bearerToken(RoutingContext context) {
    String header = context.request().getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null
        || header.length() <= BEARER_SCHEME.length()
        || !header.regionMatches(true, 0, BEARER_SCHEME, 0, BEARER_SCHEME.length())
        || header.charAt(BEARER_SCHEME.length()) != ' ') {
      return null;
    }
    String token = header.substring(BEARER_SCHEME.length() + 1).trim();
    return token.isEmpty() ? null : token;
  }

  /**
   * Motivo registrado pelo provider; qualquer outra falha de autenticação é credencial inválida.
   */
  private static ErrorCode failureCode(Object attribute) {
    return attribute instanceof ErrorCode code ? code : ErrorCode.INVALID_CREDENTIALS;
  }

  /** Mesmas mensagens do caso de uso: o corpo do 401 não inventa um texto próprio. */
  private static String detailOf(ErrorCode code) {
    return code == ErrorCode.SESSION_EXPIRED
        ? AuthenticateSessionUseCase.SESSION_EXPIRED_DETAIL
        : AuthenticateSessionUseCase.INVALID_TOKEN_DETAIL;
  }

  /** Correlação da resposta: o header do cliente ou um UUID novo, ecoado como o filtro faria. */
  private static String requestId(RoutingContext context) {
    String requestId = context.request().getHeader(RequestIdFilter.REQUEST_ID_HEADER);
    return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
  }

  private String json(ProblemDetail problem) {
    try {
      return objectMapper.writeValueAsString(problem);
    } catch (JsonProcessingException impossible) {
      // ProblemDetail é um record de tipos simples: não há JSON inválido possível aqui.
      throw new IllegalStateException("problema não serializável", impossible);
    }
  }
}
