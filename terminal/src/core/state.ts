/**
 * Estado da operação da TUI como união discriminada (§11.2): um estado por tela —
 * `login`, `openingCash`, `saleOpen`, `paying`, `closingCash` — mais `error`, que guarda o estado
 * de origem para voltar sem perder nada.
 *
 * Estes tipos descrevem a **view state** (o que as telas exibem), não DTOs da API. Nenhum valor é
 * calculado aqui: total, desconto e troco chegam prontos do servidor (BR-12).
 */

/** Operador autenticado, como veio do login. */
export type Operator = {
  id: string;
  name: string;
};

/** Caixa físico selecionado na entrada. */
export type CashRegister = {
  id: string;
  name: string;
};

/** Item da venda como o servidor devolveu. */
export type SaleItemView = {
  productId: string;
  name: string;
  /** `UN` ou `KG`: muda só a granularidade do `+`/`-` (1109/1110), nunca o cálculo (BR-12). */
  unit: string;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
};

/** Forma de pagamento do contrato (`PaymentMethod`): as cinco do PDV, escolhidas pelo operador. */
export type PaymentMethod = 'CASH' | 'PIX' | 'DEBIT' | 'CREDIT' | 'VOUCHER';

/** Pagamento registrado na venda, como o servidor devolveu — nada é calculado aqui (BR-05, BR-12). */
export type PaymentView = {
  id: string;
  method: PaymentMethod;
  amount: number;
  /** Troco do dinheiro (`tendered − amount`, BR-05); `0` nas demais formas. */
  changeAmount: number;
  /** `APPROVED`/`CANCELLED` do `PaymentStatus` (1113 não cancela: tudo chega aprovado). */
  status: string;
};

/** Venda em andamento — valores do servidor, nunca recalculados na TUI. */
export type SaleView = {
  id: string;
  items: SaleItemView[];
  subtotal: number;
  discountAmount: number;
  total: number;
  /** Pago até agora, somado pelo servidor a cada pagamento aprovado (BR-05). */
  paidAmount: number;
  /** Troco acumulado do dinheiro; `0` enquanto não houver (BR-05). */
  changeAmount: number;
  /** Pagamentos registrados, na ordem em que o servidor os devolveu. */
  payments: PaymentView[];
  /**
   * Cliente vinculado como o servidor o tem (`customerId` do `SaleDetailResponse`, 1112); `null` na
   * venda anônima. O **nome** exibido no cabeçalho não vem daqui: é a anotação local que o shell
   * guarda quando o próprio operador vincula (o reducer não tem o nome).
   */
  customerId: string | null;
};

/**
 * Resumo da venda concluída, lido do corpo do `complete` (1113): número, total e troco como o
 * servidor os devolveu — a tela de sucesso só exibe (BR-12).
 */
export type ReceiptView = {
  number: number;
  total: number;
  changeAmount: number;
};

/** Bipe do leitor aguardando a chamada da API; o código segue **bruto** (BR-14). */
export type ScanIntent = {
  barcode: string;
  quantity: number;
};

/** Falha da API no formato `problem+json` (§9.1). */
export type ApiProblem = {
  status: number;
  code: string | null;
  detail: string;
};

/** Contexto de um caixa aberto — o que venda, pagamento e fechamento compartilham. */
export type CashContext = {
  operator: Operator;
  register: CashRegister;
  sessionId: string;
};

export type LoginState = {
  kind: 'login';
  /** mensagem do último login recusado pelo servidor; o formulário continua na tela. */
  failure: string | null;
};

export type OpeningCashState = {
  kind: 'openingCash';
  operator: Operator;
  register: CashRegister;
};

export type SaleOpenState = CashContext & {
  kind: 'saleOpen';
  /** `null` antes do primeiro bipe: a venda nasce no servidor com o primeiro item. */
  sale: SaleView | null;
  /** último bipe ainda não enviado à API. */
  pendingScan: ScanIntent | null;
  /**
   * Resumo da venda que acabou de ser concluída (1113): enquanto não for `null` a tela de sucesso
   * está no lugar da venda e o ENTER inicia a próxima.
   */
  receipt: ReceiptView | null;
};

export type PayingState = CashContext & {
  kind: 'paying';
  sale: SaleView;
};

export type ClosingCashState = CashContext & {
  kind: 'closingCash';
  /** venda em andamento preservada: ESC volta para ela. */
  sale: SaleView | null;
};

/** Estado de operação — um por tela. */
export type OperatingState =
  | LoginState
  | OpeningCashState
  | SaleOpenState
  | PayingState
  | ClosingCashState;

/** Falha bloqueante: guarda o estado de origem para voltar a ele ao reconhecer o erro. */
export type ErrorState = {
  kind: 'error';
  problem: ApiProblem;
  returnTo: OperatingState;
};

export type State = OperatingState | ErrorState;

/** Estado inicial da TUI: operador ainda não autenticado. */
export const initialState: State = { kind: 'login', failure: null };
