package com.minimarket.shared.application;

import com.minimarket.shared.domain.OperationSource;
import jakarta.enterprise.context.RequestScoped;
import java.net.InetAddress;
import java.util.UUID;

/**
 * Contexto do ator da requisição (passo 302, §7.2): quem opera, de qual sessão/caixa/loja, de onde
 * veio (TUI/WEB/API) e sob qual correlação. É daqui que a auditoria (passo 303) monta o evento — o
 * caso de uso não repassa esses campos à mão.
 *
 * <p>Bean {@code @RequestScoped} preenchido uma única vez, por requisição, pelo {@code
 * OperationContextFilter} a partir da identidade montada pelo mecanismo bearer (passo 206). Fora de
 * uma requisição HTTP ninguém preenche: o ator fica nulo e a origem, {@code SYSTEM} — o passo 303
 * decide o que fazer com isso.
 *
 * <p>Os nomes dos atributos da identidade moram aqui, e não no módulo {@code auth}, porque o filtro
 * é de {@code shared}: importar {@code auth} de dentro de {@code shared} fecharia um ciclo de
 * módulos (auth já depende de shared) e quebraria o teste de fronteiras.
 */
@RequestScoped
public class OperationContext {

  /** Atributo da identidade com o id do usuário autenticado ({@code String}, UUID). */
  public static final String USER_ID_ATTRIBUTE = "userId";

  /** Atributo da identidade com o id da sessão autenticada ({@code String}, UUID). */
  public static final String AUTH_SESSION_ID_ATTRIBUTE = "sessionId";

  /** Atributo da identidade com a loja da sessão ({@code String}, UUID). */
  public static final String STORE_ID_ATTRIBUTE = "storeId";

  /** Atributo da identidade com o caixa da sessão ({@code String}, UUID; ausente sem caixa). */
  public static final String CASH_REGISTER_ID_ATTRIBUTE = "cashRegisterId";

  /**
   * Atributo da identidade com a origem do cliente da sessão ({@link OperationSource}): o provider
   * bearer traduz o cliente da sessão (TUI/WEB) para o vocabulário da auditoria.
   */
  public static final String CLIENT_ATTRIBUTE = "client";

  private UUID userId;
  private String username;
  private UUID authSessionId;
  private UUID storeId;
  private UUID cashRegisterId;
  private String requestId;
  private InetAddress ip;
  private OperationSource source = OperationSource.SYSTEM;

  /** Preenchido uma vez por requisição pelo filtro; o resto da aplicação só lê. */
  public void fill(
      UUID userId,
      String username,
      UUID authSessionId,
      UUID storeId,
      UUID cashRegisterId,
      String requestId,
      InetAddress ip,
      OperationSource source) {
    this.userId = userId;
    this.username = username;
    this.authSessionId = authSessionId;
    this.storeId = storeId;
    this.cashRegisterId = cashRegisterId;
    this.requestId = requestId;
    this.ip = ip;
    this.source = source;
  }

  /** Usuário autenticado; nulo em requisição sem identidade (login, meta). */
  public UUID userId() {
    return userId;
  }

  /** Username do principal autenticado; nulo em requisição sem identidade. */
  public String username() {
    return username;
  }

  /** Sessão autenticada que fez a requisição; nula em requisição sem identidade. */
  public UUID authSessionId() {
    return authSessionId;
  }

  /** Loja da sessão (§6.2); nula em requisição sem identidade. */
  public UUID storeId() {
    return storeId;
  }

  /** Caixa da sessão (§6.2); nulo quando o login não informou caixa. */
  public UUID cashRegisterId() {
    return cashRegisterId;
  }

  /** Id de correlação da requisição, o mesmo do {@code X-Request-Id} da resposta (passo 008). */
  public String requestId() {
    return requestId;
  }

  /** IP de origem da conexão; nulo quando o servidor não informa o endereço remoto. */
  public InetAddress ip() {
    return ip;
  }

  /** Origem da operação; fora de requisição HTTP fica {@code SYSTEM}. */
  public OperationSource source() {
    return source;
  }
}
