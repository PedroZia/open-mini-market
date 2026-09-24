import { ApiError, ApiNetworkError, ApiTimeoutError, type ProblemDetails } from './errors';

/**
 * Client HTTP da API do PDV: um `fetch` com o que a API exige e não vem pronto no runtime —
 * bearer token, `Idempotency-Key` automática, timeout, retry de leitura e `problem+json`
 * traduzido para {@link ApiError} (§9.2 do plano).
 *
 * O caminho é o do recurso (`/api/v1/...`) e o tipo da resposta sai do contrato OpenAPI
 * (`components['schemas'][...]`), nunca de um tipo escrito à mão:
 *
 * ```ts
 * const client = createApiClient({ baseUrl: 'http://localhost:8080', token: () => session.token });
 * const me = await client.get<components['schemas']['MeResponse']>('/api/v1/auth/me');
 * ```
 */

/** Tentativas totais de uma leitura: a primeira tentativa mais um retry. */
const DEFAULT_MAX_ATTEMPTS = 2;

const DEFAULT_TIMEOUT_MS = 10_000;

/**
 * Token da sessão: valor fixo ou provedor. O provedor existe porque a TUI cria o client antes do
 * login e o token só passa a existir depois dele (e é trocado no logout) — a cada requisição o
 * valor corrente é lido de novo.
 */
export type TokenProvider =
  | string
  | null
  | undefined
  | (() => string | null | undefined | Promise<string | null | undefined>);

export interface ApiClientOptions {
  /** Base da API, sem barra final: `http://localhost:8080`. */
  baseUrl: string;
  /** Enviado como `Authorization: Bearer`; vazio/nulo deixa a requisição anônima (login, meta). */
  token?: TokenProvider;
  /** Tempo máximo de cada tentativa, em ms. Default `10000`. */
  timeoutMs?: number;
  /** Tentativas totais de um GET (a 1ª + retry); `1` desliga o retry. Default `2`. */
  maxAttempts?: number;
  /** `fetch` alternativo (instrumentação/testes); default o global do runtime. */
  fetch?: typeof globalThis.fetch;
}

export interface RequestOptions {
  /** Sobrescreve a `Idempotency-Key` gerada automaticamente (POST/PUT/PATCH/DELETE). */
  idempotencyKey?: string;
}

/** O client em si: verbo → caminho → resposta tipada pelo contrato. */
export interface ApiClient {
  get<T>(path: string, options?: RequestOptions): Promise<T>;
  post<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T>;
  put<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T>;
  patch<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T>;
  delete<T>(path: string, options?: RequestOptions): Promise<T>;
}

/** Cria o client da API. Uma instância por processo basta; o token pode mudar entre chamadas. */
export function createApiClient(options: ApiClientOptions): ApiClient {
  const baseUrl = options.baseUrl.replace(/\/+$/, '');
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  const maxAttempts = Math.max(1, options.maxAttempts ?? DEFAULT_MAX_ATTEMPTS);
  const fetchImpl = options.fetch ?? globalThis.fetch;

  async function send(
    method: string,
    path: string,
    body: unknown,
    requestOptions: RequestOptions | undefined,
  ): Promise<unknown> {
    // Só leitura repete: repetir um POST duplicaria dinheiro/estoque se a resposta se perdesse —
    // quem protege escrita é a Idempotency-Key, não o retry (§8 do plano).
    const attempts = method === 'GET' ? maxAttempts : 1;

    for (let attempt = 1; ; attempt += 1) {
      try {
        return await sendOnce(method, path, body, requestOptions);
      } catch (error) {
        // Esgotou as tentativas ou o erro não é de rede/5xx: o erro é do chamador.
        if (attempt >= attempts || !isRetryable(error)) {
          throw error;
        }
      }
    }
  }

  async function sendOnce(
    method: string,
    path: string,
    body: unknown,
    requestOptions: RequestOptions | undefined,
  ): Promise<unknown> {
    const headers = new Headers({ accept: 'application/json, application/problem+json' });

    const token = await resolveToken(options.token);
    if (token) {
      headers.set('authorization', `Bearer ${token}`);
    }
    if (body !== undefined) {
      headers.set('content-type', 'application/json');
    }
    if (method !== 'GET') {
      // Toda escrita é idempotente por contrato: a chave nova por chamada é o que se sobrescreve
      // quando o chamador quer repetir a mesma operação (duplo clique, resposta perdida).
      headers.set('idempotency-key', requestOptions?.idempotencyKey ?? crypto.randomUUID());
    }

    let response: Response;
    try {
      response = await fetchImpl(`${baseUrl}${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        // O timeout é por tentativa e vai no sinal do próprio fetch: aborta a conexão pendurada.
        signal: AbortSignal.timeout(timeoutMs),
      });
    } catch (error) {
      if (isAbort(error)) {
        throw new ApiTimeoutError(timeoutMs);
      }
      throw new ApiNetworkError(error);
    }

    const text = await response.text();

    if (!response.ok) {
      throw new ApiError(response.status, parseProblem(text));
    }

    // 204 No Content (ex.: logout) não tem corpo: `undefined` é a resposta.
    return text === '' ? undefined : (JSON.parse(text) as unknown);
  }

  return {
    get: <T>(path: string, requestOptions?: RequestOptions) =>
      send('GET', path, undefined, requestOptions) as Promise<T>,
    post: <T>(path: string, body?: unknown, requestOptions?: RequestOptions) =>
      send('POST', path, body, requestOptions) as Promise<T>,
    put: <T>(path: string, body?: unknown, requestOptions?: RequestOptions) =>
      send('PUT', path, body, requestOptions) as Promise<T>,
    patch: <T>(path: string, body?: unknown, requestOptions?: RequestOptions) =>
      send('PATCH', path, body, requestOptions) as Promise<T>,
    delete: <T>(path: string, requestOptions?: RequestOptions) =>
      send('DELETE', path, undefined, requestOptions) as Promise<T>,
  };
}

/** Valor corrente do token, ou nulo quando o client ainda não tem sessão. */
async function resolveToken(provider: TokenProvider): Promise<string | null> {
  const value = typeof provider === 'function' ? await provider() : provider;
  return value ?? null;
}

/**
 * Retry só em falha de transporte e 5xx (o blip do servidor). Timeout não repete: a espera já foi
 * gasta uma vez e repetir dobraria o tempo com o operador parado na frente do caixa.
 */
function isRetryable(error: unknown): boolean {
  return error instanceof ApiNetworkError || (error instanceof ApiError && error.status >= 500);
}

/**
 * O `fetch` abortado rejeita com `TimeoutError` (do {@link AbortSignal.timeout}) — o nome é
 * checado em vez da classe para não depender do runtime.
 */
function isAbort(error: unknown): boolean {
  const name =
    typeof error === 'object' && error !== null && 'name' in error ? String(error.name) : '';
  return name === 'TimeoutError' || name === 'AbortError';
}

/** Corpo `problem+json`; vazio quando a resposta fugiu do contrato (proxy, HTML de portão). */
function parseProblem(text: string): ProblemDetails {
  try {
    const parsed: unknown = JSON.parse(text);
    return typeof parsed === 'object' && parsed !== null ? (parsed as ProblemDetails) : {};
  } catch {
    return {};
  }
}
