/**
 * Erros do client: todo erro de API vira um destes tipos — o chamador decide por `code`
 * (lógica) ou por `instanceof` (tempo esgotado × falha de rede × erro do backend).
 */

/** Item de `errors[]` do `problem+json` (§9.2 do plano): campo e mensagem legível. */
export interface ApiFieldError {
  readonly field: string;
  readonly message: string;
}

/** Corpo de erro do backend no formato RFC 9457 (`application/problem+json`), §9.2 do plano. */
export interface ProblemDetails {
  readonly type?: string;
  readonly title?: string;
  readonly status?: number;
  readonly detail?: string;
  readonly instance?: string;
  /** Código estável do erro (ex.: `PRODUCT_NOT_FOUND`) — é o que o cliente usa em lógica. */
  readonly code?: string;
  /** Correlação da requisição, para citar no suporte junto do log do servidor. */
  readonly traceId?: string;
  readonly errors?: readonly ApiFieldError[];
}

/**
 * Código usado quando a resposta de erro não é o `problem+json` do backend (proxy, portão de
 * entrada, resposta truncada): o backend sempre manda `code`, então este valor denuncia o que
 * fugiu do contrato.
 */
const UNKNOWN_ERROR_CODE = 'UNKNOWN_ERROR';

/** A API respondeu 4xx/5xx: o `problem+json` traduzido para campos estáveis. */
export class ApiError extends Error {
  /** Status HTTP da resposta. */
  readonly status: number;
  /** Código estável do backend (`UNKNOWN_ERROR` quando a resposta fugiu do contrato). */
  readonly code: string;
  readonly title: string;
  readonly detail: string;
  /** Erros de validação campo a campo (`errors[]`), vazio quando não há. */
  readonly errors: readonly ApiFieldError[];
  readonly traceId: string | undefined;

  constructor(status: number, problem: ProblemDetails = {}) {
    const title = problem.title ?? `HTTP ${status}`;
    const detail = problem.detail ?? problem.title ?? `A API respondeu ${status}.`;

    super(`${title}: ${detail}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = problem.code ?? UNKNOWN_ERROR_CODE;
    this.title = title;
    this.detail = detail;
    this.errors = problem.errors ?? [];
    this.traceId = problem.traceId;
  }
}

/** A API não respondeu dentro do tempo máximo da requisição. */
export class ApiTimeoutError extends Error {
  readonly timeoutMs: number;

  constructor(timeoutMs: number) {
    super(`A API não respondeu em ${timeoutMs} ms.`);
    this.name = 'ApiTimeoutError';
    this.timeoutMs = timeoutMs;
  }
}

/** A requisição não chegou a virar resposta: sem conexão, DNS, conexão derrubada. */
export class ApiNetworkError extends Error {
  constructor(cause: unknown) {
    super('Falha de rede ao chamar a API.', { cause });
    this.name = 'ApiNetworkError';
  }
}
