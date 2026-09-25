import type { ApiProblem } from './state';

/**
 * Tratamento central das falhas da API (1117): a mesma pergunta — *o que fazer com este problema?* —
 * respondida num lugar só, pelo `code` do `problem+json` (§9.1).
 *
 * A política é o que **nenhuma tela decide sozinha**:
 * - `session`: a sessão caiu (401) — o operador volta ao login com aviso e a venda fica preservada;
 * - `reconcile`: a operação esbarrou em idempotência/concorrência (409 `IDEMPOTENCY_KEY_REUSED` ou
 *   `CONCURRENT_MODIFICATION`) — nada se repete às cegas: o estado é relido do servidor;
 * - `retryable`: rede, timeout ou 5xx — não chegou resposta confiável; a mesma operação pode ser
 *   repetida (é o que derruba o indicador de conexão da barra de status);
 * - `blocking`: o resto — a tela de erro guarda o estado de origem (§11.4).
 *
 * A recusa que fica na própria tela ("rejected") não vira política aqui porque **só o contrato da
 * operação sabe** quais status o operador corrige ali (um 403 é recusa do modal da gaveta e, na
 * mesma TUI, motivo de tela de erro na venda): quem a decide é a camada de API de cada fluxo
 * (`rejected` × `SendFailure` do `terminalApi`), com a **mensagem** vinda da tabela daqui
 * (`problemMessage`). Assim o mapa classifica e traduz, e o contexto da operação continua mandando
 * no que é recusa.
 *
 * Nada aqui calcula nem conhece tela: é função pura sobre o `ApiProblem` (BR-12).
 */
export type ProblemKind = 'session' | 'reconcile' | 'retryable' | 'blocking';

export type ProblemPolicy = {
  kind: ProblemKind;
  /** Mensagem pt-BR para o operador: a do `code` quando há, senão a padrão do caso. */
  message: string;
};

/** Aviso do login devolvido pela queda de sessão: a venda aberta continua em memória (1117). */
const SESSION_NOTICE = 'sessão expirada — entre novamente; a venda continua aberta';

/** Credencial recusada no próprio formulário de entrada: quem errou o usuário/senha é o operador. */
const INVALID_CREDENTIALS_NOTICE = 'usuário ou senha inválidos';

/** Sem resposta do servidor: o indicador de conexão cai e o operador escolhe quando repetir (1117). */
const OFFLINE_NOTICE = 'sem conexão com o servidor — a operação pode ser repetida';

/** Códigos que exigem releitura do servidor antes de qualquer repetição (§8). */
const RECONCILE_CODES: ReadonlySet<string> = new Set([
  'IDEMPOTENCY_KEY_REUSED',
  'CONCURRENT_MODIFICATION',
]);

/**
 * Mensagens pt-BR por `code`: o operador precisa saber o que corrigir sem ver o código do erro.
 * Só entram os códigos que alguém lê — as composições específicas de uma tela (com o nome do
 * produto, do cliente, da permissão ou o `detail` do servidor) continuam nela; duplicá-las aqui
 * seria uma segunda verdade esperando divergir.
 */
const MESSAGES: Readonly<Record<string, string>> = {
  IDEMPOTENCY_KEY_REUSED: 'operação já registrada com outros dados — venda conferida no servidor',
  CONCURRENT_MODIFICATION: 'outro terminal alterou esta venda — estado conferido no servidor',
  PAYMENT_INSUFFICIENT: 'pagamento insuficiente — registre o valor que falta',
  PAYMENT_EXCEEDS_TOTAL: 'valor acima do que falta na venda — ajuste o valor',
  INVALID_TENDERED_AMOUNT: 'valor recebido inválido — o dinheiro precisa cobrir o valor do pagamento',
  SESSION_HAS_OPEN_SALES: 'há venda em andamento — cancele a venda (F4) antes de fechar',
  CASH_SESSION_NOT_OPEN: 'caixa sem sessão aberta — fale com o gerente',
  CASH_SESSION_REQUIRED: 'caixa sem sessão aberta — fale com o gerente',
  CASH_REGISTER_NOT_FOUND: 'caixa não encontrado — verifique o cadastro',
  CUSTOMER_NOT_FOUND: 'cliente não encontrado — busque de novo',
  CUSTOMER_INACTIVE: 'cliente desativado no cadastro — escolha outro',
};

/**
 * Política da falha, decidida pelo `code` e pelo status: sessão e reconciliação são casos próprios
 * (nunca "repita"), rede/5xx é retryable (é o que o indicador de conexão escuta) e o resto bloqueia.
 */
export function problemPolicy(problem: ApiProblem): ProblemPolicy {
  if (problem.status === 401) {
    return { kind: 'session', message: sessionNotice(problem) };
  }

  const code = problem.code ?? '';

  if (RECONCILE_CODES.has(code)) {
    return { kind: 'reconcile', message: MESSAGES[code] ?? problem.detail };
  }

  // status 0 é o que não veio do servidor (rede/timeout) e 5xx é o servidor fora do ar: repetir faz sentido
  if (problem.status === 0 || problem.status >= 500) {
    return { kind: 'retryable', message: OFFLINE_NOTICE };
  }

  return { kind: 'blocking', message: problemMessage(problem) ?? problem.detail };
}

/** Mensagem do `code` quando a tabela o conhece; `null` para o chamador compor a dele (ex.: 403 com permissão). */
export function problemMessage(problem: ApiProblem): string | null {
  return MESSAGES[problem.code ?? ''] ?? null;
}

/** Todo 401 derruba a sessão; só a credencial recusada no login fala de usuário e senha. */
function sessionNotice(problem: ApiProblem): string {
  return problem.code === 'INVALID_CREDENTIALS' ? INVALID_CREDENTIALS_NOTICE : SESSION_NOTICE;
}
