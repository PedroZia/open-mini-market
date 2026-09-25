import { problemPolicy } from './problems';
import type {
  ApiProblem,
  CashClosingView,
  CashContext,
  CashRegister,
  ClosingCashState,
  ErrorState,
  LoginState,
  OpeningCashState,
  OperatingState,
  Operator,
  PayingState,
  ReceiptView,
  ResumeTicket,
  SaleOpenState,
  SaleView,
  State,
} from './state';

/**
 * Ações da TUI: bipe, tecla semântica e resposta da API (§11.2).
 *
 * O leitor de código de barras (1104a) emite `barcodeScanned` com o código **bruto** (BR-14) e o
 * mapa F1–F12 (1105) resolve as teclas para `confirm` (ENTER) e `cancel` (ESC). Formulários
 * (usuário/senha, valores) ficam nas telas: o reducer só reage a fatos — inclusive `sessionExpired`
 * e `saleReconciled`, os fatos transitórios de rede/sessão do 1117.
 */
export type Action =
  | { type: 'barcodeScanned'; barcode: string; quantity: number }
  | { type: 'confirm' }
  | { type: 'cancel' }
  | { type: 'loginSucceeded'; operator: Operator; register: CashRegister }
  | { type: 'loginRejected'; message: string }
  | { type: 'cashOpened'; sessionId: string }
  | { type: 'saleUpdated'; sale: SaleView }
  /** Bipe consumido sem mexer na venda (produto não encontrado ou recusado): só limpa o pendente. */
  | { type: 'scanDismissed' }
  | { type: 'paymentStarted' }
  /** Venda concluída no servidor: leva o resumo do corpo do `complete` para a tela de sucesso (1113). */
  | { type: 'saleCompleted'; receipt: ReceiptView }
  /** ENTER na tela de sucesso: a próxima venda começa limpa, pronta para o primeiro bipe (1113). */
  | { type: 'receiptDismissed' }
  /** Venda cancelada no servidor (F4, 1115): a tela volta à venda vazia do primeiro bipe. */
  | { type: 'saleCancelled' }
  | { type: 'cashClosingStarted' }
  /** Fechamento gravado pelo servidor (1115): leva a conferência dele para a tela do fechamento. */
  | { type: 'cashCloseSucceeded'; closing: CashClosingView }
  | { type: 'cashClosed' }
  | { type: 'apiFailed'; problem: ApiProblem }
  /**
   * Sessão caiu no meio da operação (401, 1117): o login volta com o aviso e a venda em andamento
   * fica guardada para a retomada. `message` é a política do `code` (`core/problems`).
   */
  | { type: 'sessionExpired'; message: string }
  /** Venda relida do servidor depois de um 409 de idempotência/concorrência (1117). */
  | { type: 'saleReconciled'; sale: SaleView; notice: string };

/** Aviso de uma venda que voltou com a sessão nova (1117). */
const RESUMED_NOTICE = 'venda retomada — os itens foram preservados';

/**
 * Reducer puro da operação: `(estado, ação) → estado`.
 *
 * Convenções:
 * - ação que não se aplica ao estado atual é **ignorada**: devolve o mesmo objeto, sem cópia;
 * - o reducer só reage a fatos (resposta da API, bipe) e às teclas que não dependem de formulário —
 *   `cancel` (ESC) fecha modal/volta e `confirm`/`cancel` reconhecem o erro;
 * - nenhum cálculo de negócio: total, desconto e troco vêm prontos do servidor (BR-12);
 * - a falha é classificada pelo mapa central (1117): sessão derruba para o login **sem descartar** a
 *   venda, idempotência/concorrência não empilha tela de erro (o shell relê o estado) e o resto
 *   bloqueia na tela de erro guardando a origem.
 */
export function reduce(state: State, action: Action): State {
  // queda de sessão vale em qualquer tela, inclusive na de erro (1117): antes do switch
  if (action.type === 'sessionExpired') {
    return sessionExpired(state, action.message);
  }

  switch (state.kind) {
    case 'login':
      return reduceLogin(state, action);
    case 'openingCash':
      return reduceOpeningCash(state, action);
    case 'saleOpen':
      return reduceSaleOpen(state, action);
    case 'paying':
      return reducePaying(state, action);
    case 'closingCash':
      return reduceClosingCash(state, action);
    case 'error':
      return reduceError(state, action);
    default:
      return assertNever(state);
  }
}

function reduceLogin(state: LoginState, action: Action): State {
  switch (action.type) {
    case 'loginSucceeded':
      // o operador entrou de novo: a venda guardada pela queda de sessão atravessa a abertura (1117)
      return {
        kind: 'openingCash',
        operator: action.operator,
        register: action.register,
        resume: state.resume,
      };
    case 'loginRejected':
      return { ...state, failure: action.message };
    case 'apiFailed':
      return blocked(state, action.problem);
    default:
      return state;
  }
}

function reduceOpeningCash(state: OpeningCashState, action: Action): State {
  switch (action.type) {
    case 'cashOpened': {
      const ticket = state.resume;
      // a venda preservada volta só no **mesmo** caixa: ela pertence a quem a abriu (BR-11)
      const resumed =
        ticket !== undefined && ticket.registerId === state.register.id ? ticket : undefined;

      return {
        kind: 'saleOpen',
        operator: state.operator,
        register: state.register,
        sessionId: action.sessionId,
        sale: resumed?.sale ?? null,
        pendingScan: null,
        receipt: null,
        notice: resumeNotice(ticket, resumed),
      };
    }
    case 'apiFailed':
      return blocked(state, action.problem);
    default:
      return state;
  }
}

function reduceSaleOpen(state: SaleOpenState, action: Action): State {
  switch (action.type) {
    case 'barcodeScanned':
      // registra a intenção de item; interpretar o código é do servidor (BR-14)
      return {
        ...state,
        pendingScan: { barcode: action.barcode, quantity: action.quantity },
        notice: undefined,
      };
    case 'saleUpdated':
      return { ...state, sale: action.sale, pendingScan: null, notice: undefined };
    case 'saleReconciled':
      // estado conferido no servidor depois do 409: a venda é a dele, com o aviso à vista (1117)
      return { ...state, sale: action.sale, notice: action.notice };
    case 'scanDismissed':
      // o bipe não virou item (404/422): não há venda nova, só o pendente a limpar
      return state.pendingScan === null ? state : { ...state, pendingScan: null, notice: undefined };
    case 'receiptDismissed':
      // ENTER na tela de sucesso: sem resumo não há o que dispensar
      return state.receipt === null ? state : { ...state, receipt: null, notice: undefined };
    case 'saleCancelled':
      // F4: a venda do servidor foi cancelada — sem venda criada, o próximo bipe abre uma nova
      return state.sale === null
        ? state
        : { ...state, sale: null, pendingScan: null, receipt: null, notice: undefined };
    case 'paymentStarted':
      // sem venda criada nem itens não há o que pagar (BR-05: venda vazia não conclui)
      return state.sale === null || state.sale.items.length === 0
        ? state
        : { kind: 'paying', ...cashContext(state), sale: state.sale, notice: state.notice };
    case 'cashClosingStarted':
      return { kind: 'closingCash', ...cashContext(state), sale: state.sale, closing: null };
    case 'apiFailed':
      return blocked(state, action.problem);
    default:
      return state;
  }
}

function reducePaying(state: PayingState, action: Action): State {
  switch (action.type) {
    case 'saleUpdated':
      // desconto ou adição de pagamento: o servidor manda a venda atualizada
      return { ...state, sale: action.sale, notice: undefined };
    case 'saleReconciled':
      return { ...state, sale: action.sale, notice: action.notice };
    case 'saleCompleted':
      // a venda fechou: o resumo do servidor vai para a tela de sucesso da próxima venda (1113)
      return {
        kind: 'saleOpen',
        ...cashContext(state),
        sale: null,
        pendingScan: null,
        receipt: action.receipt,
      };
    case 'cancel':
      return {
        kind: 'saleOpen',
        ...cashContext(state),
        sale: state.sale,
        pendingScan: null,
        receipt: null,
        notice: state.notice,
      };
    case 'apiFailed':
      return blocked(state, action.problem);
    default:
      return state;
  }
}

function reduceClosingCash(state: ClosingCashState, action: Action): State {
  switch (action.type) {
    case 'cashCloseSucceeded':
      // o servidor gravou a conferência: a tela mostra a diferença dele e o login é a saída (1115)
      return { ...state, closing: action.closing };
    case 'cashClosed':
      // a sessão terminou: o próximo operador entra pelo login
      return { kind: 'login', failure: null };
    case 'cancel':
      // com o caixa já fechado não há venda possível: o ESC só volta enquanto a sessão está aberta
      return state.closing === null
        ? {
            kind: 'saleOpen',
            ...cashContext(state),
            sale: state.sale,
            pendingScan: null,
            receipt: null,
          }
        : state;
    case 'apiFailed':
      return blocked(state, action.problem);
    default:
      return state;
  }
}

function reduceError(state: ErrorState, action: Action): State {
  switch (action.type) {
    case 'confirm':
    case 'cancel':
      return state.returnTo;
    case 'apiFailed':
      // falha durante a falha não empilha — mas a sessão caída tem para onde levar o operador (1117)
      return problemPolicy(action.problem).kind === 'session'
        ? sessionExpired(state.returnTo, problemPolicy(action.problem).message)
        : state;
    default:
      return state;
  }
}

/**
 * Fim de qualquer falha bloqueante: o mapa central decide (1117). Sessão cai para o login
 * preservando a venda, um 409 de idempotência/concorrência fica na tela (o shell relê o estado do
 * servidor e nada é repetido) e o resto vai para a tela de erro levando o estado de origem.
 */
function blocked(state: OperatingState, problem: ApiProblem): State {
  const policy = problemPolicy(problem);

  if (policy.kind === 'session') {
    return sessionExpired(state, policy.message);
  }

  if (policy.kind === 'reconcile') {
    return state;
  }

  return { kind: 'error', problem, returnTo: state };
}

/** Queda de sessão (401): o login volta com o aviso e a venda em andamento fica guardada (1117). */
function sessionExpired(state: State, message: string): State {
  if (state.kind === 'login') {
    // já no login: só o aviso muda — a venda guardada (se houver) continua onde está
    return { ...state, failure: null, notice: message };
  }

  const resume = keptResume(state);

  return resume === undefined
    ? { kind: 'login', failure: null, notice: message }
    : { kind: 'login', failure: null, notice: message, resume };
}

/** O que a queda de sessão preserva: o caixa e a venda em andamento da tela de origem (1117). */
function keptResume(state: State): ResumeTicket | undefined {
  if (state.kind === 'error') {
    // a falha que estava à vista escondia uma tela; é ela que guarda a venda
    return keptResume(state.returnTo);
  }

  if (state.kind === 'openingCash') {
    return state.resume;
  }

  if (state.kind === 'saleOpen' || state.kind === 'paying' || state.kind === 'closingCash') {
    return { registerId: state.register.id, registerName: state.register.name, sale: state.sale };
  }

  return undefined;
}

/** Aviso da retomada: a venda voltou, ou ficou no caixa de origem porque aqui ela não é operável. */
function resumeNotice(
  ticket: ResumeTicket | undefined,
  resumed: ResumeTicket | undefined,
): string | undefined {
  if (resumed !== undefined) {
    return RESUMED_NOTICE;
  }

  if (ticket === undefined) {
    return undefined;
  }

  return `a venda aberta no caixa ${ticket.registerName} não foi retomada aqui — conclua-a naquele caixa`;
}

/** Só o contexto do caixa: a tela seguinte não herda os demais campos de quem a chamou. */
function cashContext(state: CashContext): CashContext {
  return { operator: state.operator, register: state.register, sessionId: state.sessionId };
}

function assertNever(state: never): never {
  throw new Error(`estado não tratado pela máquina da TUI: ${JSON.stringify(state)}`);
}
