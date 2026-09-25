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
  SaleOpenState,
  SaleView,
  State,
} from './state';

/**
 * Ações da TUI: bipe, tecla semântica e resposta da API (§11.2).
 *
 * O leitor de código de barras (1104a) emite `barcodeScanned` com o código **bruto** (BR-14) e o
 * mapa F1–F12 (1105) resolve as teclas para `confirm` (ENTER) e `cancel` (ESC). Formulários
 * (usuário/senha, valores) ficam nas telas: o reducer só reage a fatos.
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
  | { type: 'apiFailed'; problem: ApiProblem };

/**
 * Reducer puro da operação: `(estado, ação) → estado`.
 *
 * Convenções:
 * - ação que não se aplica ao estado atual é **ignorada**: devolve o mesmo objeto, sem cópia;
 * - o reducer só reage a fatos (resposta da API, bipe) e às teclas que não dependem de formulário —
 *   `cancel` (ESC) fecha modal/volta e `confirm`/`cancel` reconhecem o erro;
 * - nenhum cálculo de negócio: total, desconto e troco vêm prontos do servidor (BR-12).
 */
export function reduce(state: State, action: Action): State {
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
      return { kind: 'openingCash', operator: action.operator, register: action.register };
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
    case 'cashOpened':
      return {
        kind: 'saleOpen',
        operator: state.operator,
        register: state.register,
        sessionId: action.sessionId,
        sale: null,
        pendingScan: null,
        receipt: null,
      };
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
      return { ...state, pendingScan: { barcode: action.barcode, quantity: action.quantity } };
    case 'saleUpdated':
      return { ...state, sale: action.sale, pendingScan: null };
    case 'scanDismissed':
      // o bipe não virou item (404/422): não há venda nova, só o pendente a limpar
      return state.pendingScan === null ? state : { ...state, pendingScan: null };
    case 'receiptDismissed':
      // ENTER na tela de sucesso: sem resumo não há o que dispensar
      return state.receipt === null ? state : { ...state, receipt: null };
    case 'saleCancelled':
      // F4: a venda do servidor foi cancelada — sem venda criada, o próximo bipe abre uma nova
      return state.sale === null
        ? state
        : { ...state, sale: null, pendingScan: null, receipt: null };
    case 'paymentStarted':
      // sem venda criada nem itens não há o que pagar (BR-05: venda vazia não conclui)
      return state.sale === null || state.sale.items.length === 0
        ? state
        : { kind: 'paying', ...cashContext(state), sale: state.sale };
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
      return { ...state, sale: action.sale };
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
    default:
      // falha durante a falha não empilha: o operador reconhece a primeira antes
      return state;
  }
}

/** Entra em erro levando o estado de origem: reconhecer o erro volta para ele sem perder nada. */
function blocked(state: OperatingState, problem: ApiProblem): ErrorState {
  return { kind: 'error', problem, returnTo: state };
}

/** Só o contexto do caixa: a tela seguinte não herda os demais campos de quem a chamou. */
function cashContext(state: CashContext): CashContext {
  return { operator: state.operator, register: state.register, sessionId: state.sessionId };
}

function assertNever(state: never): never {
  throw new Error(`estado não tratado pela máquina da TUI: ${JSON.stringify(state)}`);
}
