import { ApiError, ApiNetworkError, ApiTimeoutError } from '@minimarket/api-client';

/**
 * `problem+json` (§9.2) traduzido para a tela. Toda mensagem nasce do `code` estável — nunca do
 * `detail` sozinho, que pode carregar identificador interno — e o texto é o que o operador lê.
 *
 * Quem chama em formulário usa {@link fieldErrors} para pintar os campos; quem mostra o resto
 * deixa o texto de {@link errorMessage} ir para o toast (via caches do Query, `app/query-client`).
 */

/** Mensagem por código quando o `detail` do servidor não serve direto para o operador. */
const CODE_MESSAGES: Record<string, string> = {
  VALIDATION_ERROR: 'Confira os campos destacados e tente de novo.',
  ACCESS_DENIED: 'Você não tem permissão para esta operação.',
  RATE_LIMITED: 'Muitas requisições em pouco tempo. Aguarde um instante e tente de novo.',
  IF_MATCH_REQUIRED: 'O registro mudou desde que a tela foi aberta. Recarregue e tente de novo.',
  CONCURRENT_MODIFICATION: 'Alguém alterou este registro antes de você. Recarregue e tente de novo.',
  CONFLICT: 'A operação não cabe no estado atual do registro. Recarregue e tente de novo.',
  IDEMPOTENCY_KEY_REUSED: 'Esta operação já foi registrada antes. Atualize a lista.',
  IDEMPOTENCY_KEY_REQUIRED: 'A operação não pôde ser repetida com segurança. Tente de novo.',
  SESSION_EXPIRED: 'A sessão expirou. Entre de novo.',
  SESSION_IDLE_TIMEOUT: 'A sessão expirou por inatividade. Entre de novo.',
  ACCOUNT_LOCKED: 'Conta bloqueada por tentativas de login. Tente de novo mais tarde.',
  INVALID_CREDENTIALS: 'Usuário ou senha inválidos.',
  // Resposta fora do contrato (proxy/HTML): o `detail` do client é só o status HTTP.
  UNKNOWN_ERROR: 'A resposta do servidor veio fora do padrão. Tente de novo.',
};

const GENERIC_MESSAGE = 'Não foi possível concluir a operação. Tente de novo.';

/** Códigos terminados em `_NOT_FOUND` (e o `NOT_FOUND` genérico) ganham o mesmo recado. */
function isNotFound(code: string): boolean {
  return code === 'NOT_FOUND' || code.endsWith('_NOT_FOUND');
}

/** Mensagem clara para o operador a partir do erro como ele chegou do client. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const mapped = CODE_MESSAGES[error.code];
    if (mapped !== undefined) {
      return mapped;
    }
    if (isNotFound(error.code)) {
      return 'O registro não existe mais. Atualize a lista.';
    }
    // 5xx pode trazer detalhe técnico no `detail`: nunca vai para a tela.
    if (error.status >= 500) {
      return 'O servidor falhou ao responder. Tente de novo em instantes.';
    }
    // 4xx do backend: o `detail` é escrito para humano (§9.2).
    return error.detail;
  }
  if (error instanceof ApiTimeoutError) {
    return 'O servidor não respondeu a tempo. Tente de novo.';
  }
  if (error instanceof ApiNetworkError) {
    return 'Não foi possível falar com o servidor. Verifique a conexão.';
  }
  return GENERIC_MESSAGE;
}

/**
 * Erros de validação por campo (`errors[]` do `problem+json`) para os formulários. Vazio quando o
 * erro não trouxe validação — o primeiro erro de cada campo é o que fica.
 */
export function fieldErrors(error: unknown): Record<string, string> {
  const byField: Record<string, string> = {};
  if (error instanceof ApiError) {
    for (const { field, message } of error.errors) {
      if (!(field in byField)) {
        byField[field] = message;
      }
    }
  }
  return byField;
}

/** `true` para 401: quem reage é a sessão (1202), não o toast. */
export function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}

/** `true` para 403: a tela mostra o estado "sem permissão" (`NoPermission`), não o toast. */
export function isForbidden(error: unknown): boolean {
  return error instanceof ApiError && error.status === 403;
}
