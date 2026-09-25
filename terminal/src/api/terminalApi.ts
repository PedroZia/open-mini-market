import { ApiError, type ApiClient, type components } from '@minimarket/api-client';

import { problemMessage, problemPolicy } from '../core/problems';
import type {
  ApiProblem,
  CashClosingView,
  Operator,
  PaymentMethod,
  ReceiptView,
  SaleView,
} from '../core/state';
import { clearToken, setToken } from './session';

/**
 * Camada de API da TUI: o contrato do backend (`components['schemas'][...]`, gerado no
 * `@minimarket/api-client`) traduzido para o que as telas precisam — operador autenticado, caixas
 * disponíveis e a sessão de caixa — com as falhas já separadas em "recusa" (a tela fica e avisa) e
 * "falha" (tela de erro, §11.4). A sessão caída e os 409 de idempotência/concorrência nunca são
 * recusa do operador: o mapa central (`core/problems`, 1117) os roteia para o shell, que volta ao
 * login preservando a venda ou relê o estado do servidor. Nenhuma regra de negócio aqui (BR-12): a
 * TUI não calcula nada, só transporta o que o servidor respondeu.
 *
 * A instância do client entra por parâmetro (`createTerminalApi(client)`): os testes injetam um
 * dublê e o app injeta a instância única de `src/api/index.ts`.
 */

/** Caixa como a lista de escolha mostra: o `CashRegisterResponse` já normalizado. */
export type CashRegisterOption = {
  id: string;
  code: string;
  name: string;
  /** Sessão aberta agora (`OPEN`); o servidor é a fonte, a TUI só exibe. */
  open: boolean;
  /** Quem abriu a sessão aberta; `null` com o caixa livre. */
  operatorName: string | null;
};

/** Resultado do login: sucesso com o operador, recusa (400/401/423) ou falha bloqueante. */
export type LoginOutcome =
  | { ok: true; operator: Operator }
  /** 401 `INVALID_CREDENTIALS`, 423 `ACCOUNT_LOCKED` ou 400 do `cashRegisterId`: a mensagem fica na tela, o formulário continua. */
  | { ok: false; kind: 'rejected'; message: string }
  /** Rede, timeout, 5xx (e o resto dos 4xx): bloqueia e vai para a tela de erro. */
  | { ok: false; kind: 'failed'; problem: ApiProblem };

/** Resultado da lista de caixas: qualquer falha bloqueia. */
export type CashRegistersOutcome =
  | { ok: true; registers: CashRegisterOption[] }
  | { ok: false; problem: ApiProblem };

/** Resultado da abertura do caixa: sessão criada, caixa já aberto (409) ou falha bloqueante. */
export type OpenCashRegisterOutcome =
  | { ok: true; sessionId: string }
  /** 409 `CASH_REGISTER_ALREADY_OPEN`: a tela busca a sessão existente e segue com ela (1107). */
  | { ok: false; kind: 'alreadyOpen' }
  | { ok: false; kind: 'failed'; problem: ApiProblem };

/** Resultado da sessão corrente do caixa: qualquer falha bloqueia. */
export type CurrentCashSessionOutcome =
  | { ok: true; sessionId: string }
  | { ok: false; problem: ApiProblem };

/**
 * Produto do bipe (passos 409, 1104b3 e 1116): o que a tela precisa para mostrar o item, o
 * autoteste ou a consulta de preço — o `id` é o que o saldo do F2 usa e a `unit` é o rótulo que a
 * consulta exibe (nunca cálculo, BR-12).
 */
export type BarcodeProduct = {
  id: string;
  name: string;
  price: number;
  unit: string;
  /** Quantidade sugerida pela etiqueta de balança; `null` fora dela (BR-14). */
  quantity: number | null;
};

/**
 * Resultado do bipe (`GET /products/barcode/{barcode}`, passo 409):
 * - `ok`: o produto resolvido pelo servidor (§9.2);
 * - `notFound`: 404 `PRODUCT_NOT_FOUND` ou 422 `INVALID_INTERNAL_BARCODE` — o código não é
 *   conhecido; o autoteste exibe o `problem` e a consulta de preço (F2) segue pelo nome (1116);
 * - `SendFailure`: retry manual (rede/5xx) ou tela de erro (403/400/contrato).
 */
export type BarcodeLookupOutcome =
  | { ok: true; product: BarcodeProduct }
  | { ok: false; kind: 'notFound'; problem: ApiProblem }
  | SendFailure;

/** O que o bipe manda ao servidor (`SaleItemRequest` do contrato): código **bruto** e quantidade (BR-14). */
export type SaleItemIntent = {
  barcode: string;
  quantity: number;
};

/**
 * Falha de envio (abrir a venda ou incluir o item): `retryable` é a que não completou no servidor —
 * rede, timeout e 5xx —, com o bipe guardado para o retry manual da tela (1109); `failed` (403, 409,
 * 400, contrato) bloqueia a operação na tela de erro (§11.4).
 */
export type SendFailure =
  | { ok: false; kind: 'retryable'; problem: ApiProblem }
  | { ok: false; kind: 'failed'; problem: ApiProblem };

/** Resultado de abrir a venda no primeiro bipe (`POST /sales`, 201): a venda vazia ou a falha. */
export type CreateSaleOutcome = { ok: true; sale: SaleView } | SendFailure;

/**
 * Resultado do bipe na venda:
 * - `ok`: a venda inteira como o servidor devolveu (itens e totais dele, BR-12);
 * - `notFound`: 404 `PRODUCT_NOT_FOUND` — a tela avisa e a venda continua;
 * - `rejected`: 422 `PRODUCT_INACTIVE`/`INVALID_INTERNAL_BARCODE` — aviso com mensagem clara;
 * - `SendFailure`: retry manual (rede/5xx) ou tela de erro (403/409/400/contrato).
 */
export type AddSaleItemOutcome =
  | { ok: true; sale: SaleView }
  | { ok: false; kind: 'notFound'; barcode: string }
  | { ok: false; kind: 'rejected'; barcode: string; message: string }
  | SendFailure;

/**
 * Resultado de mexer num item que já está na venda — trocar a quantidade (`PATCH`) ou removê-lo
 * (`DELETE`, passo 1110): as duas rotas devolvem o mesmo `SaleDetailResponse` e recusam do mesmo
 * jeito.
 * - `ok`: a venda inteira como o servidor recalculou (BR-12);
 * - `notFound`: 404 `SALE_ITEM_NOT_FOUND` — o item sumiu entre a leitura da tela e a ação (a venda
 *   pode ter sido mexida em outro terminal); a tela avisa e nada muda aqui;
 * - `SendFailure`: retry manual (rede/5xx) ou tela de erro (403 `ACCESS_DENIED`, 409
 *   `SALE_NOT_OPEN`, 400/contrato).
 */
export type SaleItemMutationOutcome =
  | { ok: true; sale: SaleView }
  | { ok: false; kind: 'notFound' }
  | SendFailure;

/** Tipo do desconto como o contrato o define (`DiscountType`, passo 811b): valor ou percentual. */
export type DiscountType = 'VALUE' | 'PERCENT';

/**
 * O que o modal de desconto manda (`SaleDiscountRequest`, passo 811b): o valor informado pelo
 * operador e o motivo obrigatório (BR-04). Quem calcula desconto e total é o servidor (BR-12).
 */
export type SaleDiscountIntent = {
  type: DiscountType;
  /** Em reais no `VALUE` (`10.5`) ou percentual inteiro no `PERCENT` (`10` = 10%). */
  value: number;
  reason: string;
};

/**
 * Resultado de aplicar o desconto (passo 1111):
 * - `ok`: a venda inteira com o desconto e os totais recalculados pelo servidor (BR-12);
 * - `rejected`: recusa com a mensagem que o **próprio modal** mostra — 403 sem
 *   `sale.discount.apply` (matriz do 810), 422 `DISCOUNT_LIMIT_EXCEEDED` do limite da loja e 400 da
 *   forma/motivo —, sem fechar o formulário;
 * - `SendFailure`: retry manual no modal (rede/5xx) ou tela de erro (404/409/contrato), como nos
 *   demais envios (1109/1110).
 */
export type ApplyDiscountOutcome =
  | { ok: true; sale: SaleView }
  | { ok: false; kind: 'rejected'; message: string }
  | SendFailure;

/** Cliente como a busca do F6 mostra (1112): nome e CPF do `CustomerResponse` já normalizados. */
export type CustomerOption = {
  id: string;
  name: string;
  /** CPF só com dígitos (`12345678900`), como o servidor o guarda; a máscara é da apresentação. */
  taxId: string | null;
};

/**
 * Resultado da busca de clientes (`GET /customers?search=&page=&size=`, passo 502):
 * - `ok`: a página que o servidor devolveu (a busca dele cobre trecho do nome e dígitos do CPF);
 * - `rejected`: 403 sem `customer.read` — o modal diz que não dá para buscar;
 * - `SendFailure`: retry manual no modal (rede/5xx) ou tela de erro (400/contrato).
 */
export type SearchCustomersOutcome =
  | { ok: true; customers: CustomerOption[] }
  | { ok: false; kind: 'rejected'; message: string }
  | SendFailure;

/**
 * Resultado de vincular/remover o cliente da venda aberta (`PUT`/`DELETE
 * /sales/{id}/customer`, passo 811b):
 * - `ok`: a venda inteira como o servidor a devolveu, já com o `customerId` novo;
 * - `rejected`: a recusa que **o próprio modal** mostra — 404 `CUSTOMER_NOT_FOUND`, 422
 *   `CUSTOMER_INACTIVE` e 403 sem `sale.create`;
 * - `SendFailure`: retry manual no modal (rede/5xx) ou tela de erro (404 `SALE_NOT_FOUND`, 409
 *   `SALE_NOT_OPEN`, 400/contrato), como nas demais operações da venda.
 */
export type CustomerSaleOutcome =
  | { ok: true; sale: SaleView }
  | { ok: false; kind: 'rejected'; message: string }
  | SendFailure;

/** O que o pagamento manda ao servidor (`SalePaymentRequest`, passo 905): forma, valor e o recebido do dinheiro. */
export type SalePaymentIntent = {
  method: PaymentMethod;
  /** Valor que este pagamento cobre, em reais — o máximo é o restante que o servidor calcula (BR-12). */
  amount: number;
  /**
   * Valor entregue pelo cliente no dinheiro (`CASH`); nas demais formas o contrato o recusa com 422
   * `INVALID_TENDERED_AMOUNT`. Quem calcula o troco é o servidor (BR-05).
   */
  tenderedAmount?: number;
};

/**
 * Resultado de registrar o pagamento (passo 905, 201 com a venda inteira):
 * - `ok`: a venda como o servidor a devolveu, com `paidAmount`/`changeAmount`/`payments` recalculados
 *   (BR-05, BR-12);
 * - `rejected`: a recusa que **a própria tela de pagamento** mostra — 400 da forma/valor, 403 sem
 *   `payment.add` e 422 `PAYMENT_EXCEEDS_TOTAL`/`INVALID_TENDERED_AMOUNT` —, sem sair do pagamento;
 * - `SendFailure`: rede/5xx (retry manual com a **mesma** chave) ou 404/409, também na tela, com o
 *   `detail` do servidor na frente do operador.
 */
export type AddPaymentOutcome =
  | { ok: true; sale: SaleView }
  | { ok: false; kind: 'rejected'; message: string }
  | SendFailure;

/**
 * Resultado de concluir a venda (passo 907, 200 com a venda inteira):
 * - `ok`: o resumo do corpo do `complete` — número, total e troco do servidor — para a tela de
 *   sucesso (BR-12);
 * - `rejected`: 422 `PAYMENT_INSUFFICIENT` (o mais comum: falta pagamento, BR-05), 400 do contrato e
 *   403 sem `sale.complete` — mensagem clara e o operador continua no pagamento;
 * - `SendFailure`: rede/5xx (retry manual com a **mesma** `Idempotency-Key`) ou 404/409, também na
 *   tela de pagamento.
 */
export type CompleteSaleOutcome =
  | { ok: true; receipt: ReceiptView }
  | { ok: false; kind: 'rejected'; message: string }
  | SendFailure;

/** Movimento da gaveta (F7/F8, passo 1114): sangria retira e suprimento coloca dinheiro no caixa. */
export type CashMovementKind = 'withdrawal' | 'supply';

/**
 * O que o modal da gaveta manda (`CashMovementRequest`, passo 610): o valor informado pelo operador
 * — sempre positivo, o sinal do tipo é do ledger (609/610) — e o motivo obrigatório (BR-10).
 */
export type CashMovementIntent = {
  /** Em reais (`10` = R$ 10,00). Quem soma ao saldo é o servidor (BR-12). */
  amount: number;
  reason: string;
};

/** Movimento gravado, como o servidor o devolveu no 201 (`CashMovementResponse`, passos 609/610). */
export type CashMovementView = {
  sessionId: string;
  type: string;
  amount: number;
  reason: string;
  /** Esperado da sessão antes do movimento — do servidor, da mesma transação (BR-12). */
  expectedBefore: number;
  /** Esperado depois do movimento: é este saldo atualizado que o modal mostra (BR-12). */
  expectedAfter: number;
  /** Sangria acima do esperado: o servidor **não** bloqueia, devolve o alerta (609). */
  aboveExpected: boolean;
};

/**
 * Resultado de sangrar/suprir (passo 1114):
 * - `ok`: o movimento gravado com o esperado antes/depois que o servidor calculou (BR-12);
 * - `rejected`: a recusa que **o próprio modal** mostra — 403 sem `cash.withdrawal`/`cash.supply`
 *   (BR-10), 400 do valor/motivo (forma), 404 `CASH_SESSION_NOT_OPEN`/`CASH_REGISTER_NOT_FOUND` e 409
 *   `CASH_SESSION_REQUIRED` da sessão do caixa que mudou por fora —, sem fechar o formulário;
 * - `SendFailure`: retry manual no modal (rede/5xx) ou tela de erro (contrato), como nos demais
 *   envios.
 */
export type CashMovementOutcome =
  | { ok: true; movement: CashMovementView }
  | { ok: false; kind: 'rejected'; message: string }
  | SendFailure;

/**
 * Resumo do fechamento como o servidor o devolve (`CashSessionSummaryResponse`, passos 612/909): é o
 * que a tela do F10 mostra antes de o operador digitar o contado — o esperado e as quebras são dele
 * (BR-12), a TUI só exibe. `countedAmount`/`differenceAmount` são nulos enquanto a sessão está
 * aberta (o contado só existe no fechamento).
 */
export type CashSessionSummaryView = {
  sessionId: string;
  /** `OPEN`/`CLOSED` do contrato. */
  status: string;
  /** Fundo de troco da abertura. */
  openingAmount: number;
  /** Esperado na gaveta, recalculado pelo servidor (BR-12). */
  expectedAmount: number;
  countedAmount: number | null;
  differenceAmount: number | null;
  /** Totais por tipo de movimento (`OPENING`/`SALE`/`WITHDRAWAL`/`SUPPLY`), zero-preenchidos. */
  totalsByType: Record<string, number>;
  /** Vendas da sessão por forma de pagamento: as cinco formas, zero-preenchidas (passo 909). */
  paymentsByMethod: Record<string, number>;
};

/**
 * Resultado do resumo: leitura cuja falha bloqueia como a da sessão corrente (1107) — sem o resumo
 * não há conferência para mostrar, e a tela de erro volta para o fechamento, que o busca de novo.
 */
export type CashSessionSummaryOutcome =
  | { ok: true; summary: CashSessionSummaryView }
  | { ok: false; problem: ApiProblem };

/**
 * O que o fechamento manda (`CloseCashSessionRequest`, passo 611): o valor contado pelo operador e a
 * observação opcional. Quem calcula o esperado e a diferença é o servidor (BR-12).
 */
export type CloseCashSessionIntent = {
  /** Em reais (`10` = R$ 10,00). */
  countedAmount: number;
  notes?: string;
};

/**
 * Resultado do fechamento (`POST /cash-registers/{id}/close`, 200 com o `CashSessionDetailResponse`,
 * passo 611):
 * - `ok`: a conferência do servidor — contado, esperado e a **diferença dele** (BR-12);
 * - `rejected`: a recusa que **a própria tela** mostra — 403 sem `cash.close`, 400 do valor e 409
 *   `SESSION_HAS_OPEN_SALES`/`CASH_SESSION_ALREADY_CLOSED` —, sem sair do fechamento;
 * - `SendFailure`: retry manual com a **mesma** `Idempotency-Key` (rede/5xx) ou tela de erro
 *   (contrato), como nos demais envios de dinheiro.
 */
export type CloseCashSessionOutcome =
  | { ok: true; closing: CashClosingView }
  | { ok: false; kind: 'rejected'; message: string }
  | SendFailure;

/**
 * Resultado do cancelamento da venda aberta (F4, `POST /sales/{id}/cancel`, 200, passo 813):
 * - `ok`: a venda foi cancelada; a tela volta à venda vazia (o corpo traz a venda cancelada, que a
 *   TUI não exibe — ela não tem mais venda);
 * - `rejected`: a recusa que **o próprio modal** mostra — 403 sem `sale.cancel` (BR-04: quem opera
 *   não cancela) e 400 do motivo;
 * - `SendFailure`: retry manual com a **mesma** chave (idempotente: repetir devolve replay) ou tela
 *   de erro (404 `SALE_NOT_FOUND`/409 `SALE_NOT_OPEN`, contrato), como nas demais operações da venda.
 */
export type CancelSaleOutcome =
  | { ok: true }
  | { ok: false; kind: 'rejected'; message: string }
  | SendFailure;

/** Produto como a consulta de preço (F2) o exibe: o `ProductResponse`/barcode normalizados (1116). */
export type ProductOption = {
  id: string;
  name: string;
  price: number;
  /** `UN` ou `KG` do cadastro: rótulo da apresentação, nunca cálculo (BR-12). */
  unit: string;
};

/**
 * Resultado da busca de produtos por nome (F2, `GET /products?search=&size=`, passo 404):
 * - `ok`: os produtos da página que o servidor devolveu (a busca dele cobre trecho do nome);
 * - `rejected`: 403 sem `product.read` — o modal diz que não dá para consultar;
 * - `SendFailure`: retry manual no modal (rede/5xx) ou tela de erro (400/contrato).
 */
export type SearchProductsOutcome =
  | { ok: true; products: ProductOption[] }
  | { ok: false; kind: 'rejected'; message: string }
  | SendFailure;

/** Saldo do produto como o `StockDetailResponse` o traz (passo 704): tudo do servidor (BR-12). */
export type StockBalanceView = {
  quantity: number;
  minQuantity: number;
  /** Estoque baixo: sinal calculado pelo servidor, a TUI só exibe. */
  lowStock: boolean;
};

/**
 * Resultado do saldo (F2, `GET /stock/{productId}`, passo 704):
 * - `ok`: saldo e mínimo do servidor, com o aviso de estoque baixo dele (BR-12);
 * - `rejected`: 403 sem `stock.read` e 404 `PRODUCT_NOT_FOUND` do produto que sumiu entre a busca
 *   e o saldo — o modal avisa sem fechar;
 * - `SendFailure`: retry manual no modal (rede/5xx) ou tela de erro (400/contrato).
 */
export type ProductStockOutcome =
  | { ok: true; stock: StockBalanceView }
  | { ok: false; kind: 'rejected'; message: string }
  | SendFailure;

/** Loja da sessão corrente (`GET /auth/me`, 1117): o que o cabeçalho da venda mostra. */
export type StoreView = {
  code: string;
  name: string;
};

/**
 * Resultado da sessão corrente (`GET /api/v1/auth/me`, passos 207/1117):
 * - `ok`: a loja da sessão como o servidor a devolveu; `store` é `null` quando a resposta não a traz
 *   — o cabeçalho fica sem loja e a venda não para por isso;
 * - `{ ok: false }`: qualquer falha; o shell ignora (é rótulo, não operação).
 */
export type SessionOutcome =
  | { ok: true; store: StoreView | null }
  | { ok: false; problem: ApiProblem };

/**
 * Resultado da releitura da venda (`GET /api/v1/sales/{id}`, passo 812, usado pela reconciliação do
 * 1117): a venda inteira como o servidor a tem agora, ou a falha que impede a releitura.
 */
export type SaleReloadOutcome =
  | { ok: true; sale: SaleView }
  | { ok: false; problem: ApiProblem };

/** O que as telas usam da API; em teste, um dublê com esta cara. */
export type TerminalApi = {
  /**
   * Autentica no servidor e **guarda o token em memória**. Sem `cashRegisterId`, a sessão nasce
   * provisória (só lista caixas); com ele, nasce vinculada ao caixa (passo 607) — é o login que a
   * tela refaz depois de escolher o caixa (1107).
   */
  login(username: string, password: string, cashRegisterId?: string): Promise<LoginOutcome>;
  /**
   * Sessão corrente (`GET /api/v1/auth/me`, passo 207) só para o que o cabeçalho exibe (1117): a
   * loja do operador. Falha não bloqueia nada — sem a resposta o cabeçalho fica sem loja.
   */
  currentSession(): Promise<SessionOutcome>;
  /**
   * Revoga a sessão provisória (`POST /auth/logout`, 204) e **esquece o token local mesmo se a
   * revogação falhar**: a sessão é descartável e o token revogado não pode sobrar para o próximo
   * login (o mecanismo bearer responderia 401 antes do recurso).
   */
  logout(): Promise<void>;
  /** Caixas ativos da loja, com o status da sessão atual e o operador dela. */
  listCashRegisters(): Promise<CashRegistersOutcome>;
  /** Abre a sessão do caixa com o fundo de troco informado (`POST .../open`, 201). */
  openCashRegister(registerId: string, openingAmount: number): Promise<OpenCashRegisterOutcome>;
  /** Sessão aberta agora no caixa (`GET .../current-session`); sem sessão, a falha é bloqueante. */
  currentCashSession(registerId: string): Promise<CurrentCashSessionOutcome>;
  /**
   * Resolve o código **bruto** do bipe no caminho quente do PDV (`GET /products/barcode/{barcode}`,
   * passo 409): o servidor decide se é GTIN, código interno ou etiqueta de balança e devolve a
   * quantidade sugerida quando a etiqueta embute peso ou preço (BR-14).
   *
   * - `ok`: o produto como o servidor o devolveu — o autoteste (F11) e a consulta de preço (F2),
   *   que usa o `id` para buscar o saldo (1116);
   * - `notFound`: 404/422 — o termo não é um código conhecido; o F2 segue tratando-o como nome;
   * - `SendFailure`: retry manual (rede/5xx) ou tela de erro (403/400/contrato).
   */
  resolveBarcode(barcode: string): Promise<BarcodeLookupOutcome>;
  /**
   * Busca produtos por trecho do nome (`GET /products`, passo 404) na consulta de preço (F2): o
   * termo vai **como o operador digitou** — quem decide o que é nome é o servidor —, com a primeira
   * página pequena, que é o que cabe no modal (1116).
   */
  searchProducts(term: string): Promise<SearchProductsOutcome>;
  /**
   * Saldo do produto (`GET /stock/{productId}`, passo 704) para a consulta de preço (F2): a
   * quantidade, o mínimo e o aviso de estoque baixo são do servidor (BR-12) — a TUI só exibe.
   */
  productStock(productId: string): Promise<ProductStockOutcome>;
  /**
   * Abre a venda (`POST /sales`, 201, sem corpo) no primeiro bipe: a resposta só traz os totais
   * zerados (ainda sem itens), e é o id dela que o `addSaleItem` usa. A `Idempotency-Key` é do
   * client (1102), uma por chamada.
   */
  createSale(): Promise<CreateSaleOutcome>;
  /**
   * Venda inteira do servidor (`GET /api/v1/sales/{id}`, passo 812): a releitura da reconciliação
   * (1117), usada quando um 409 de idempotência/concorrência diz que o estado local não vale mais.
   */
  getSale(saleId: string): Promise<SaleReloadOutcome>;
  /**
   * Inclui o item pelo código **bruto** do bipe (BR-14): o servidor resolve GTIN, código interno ou
   * etiqueta de balança e soma na linha do produto que já está na venda (BR-01).
   */
  addSaleItem(saleId: string, item: SaleItemIntent): Promise<AddSaleItemOutcome>;
  /**
   * Troca a quantidade de um item que já está na venda (`PATCH /sales/{id}/items/{itemId}`, 200): a
   * quantidade vai **absoluta** — nada de soma local — e quem recalcula linha e totais é o servidor
   * (BR-02, BR-12). O `{itemId}` do contrato é o `productId` do item (decisão do 802/809b).
   */
  changeSaleItemQuantity(
    saleId: string,
    productId: string,
    quantity: number,
  ): Promise<SaleItemMutationOutcome>;
  /**
   * Remove o item da venda aberta (`DELETE /sales/{id}/items/{itemId}`, 200): a venda inteira
   * volta na resposta, com os totais já recalculados pelo servidor. Remover o último item deixa a
   * venda vazia — quem decide voltar ao estado vazio é a tela (1108).
   */
  removeSaleItem(saleId: string, productId: string): Promise<SaleItemMutationOutcome>;
  /**
   * Aplica o desconto na venda aberta (`PUT /sales/{id}/discount`, passo 811b): o tipo, o valor
   * informado e o motivo obrigatório (BR-04) vão no corpo, mas quem calcula o desconto e o total é
   * o servidor (BR-03/BR-12) — o limite da loja é dele, não da TUI. A resposta é a venda inteira
   * recalculada, que a tela registra com `saleUpdated` (1111).
   */
  applyDiscount(saleId: string, discount: SaleDiscountIntent): Promise<ApplyDiscountOutcome>;
  /**
   * Busca clientes por trecho do nome ou dígitos do CPF (`GET /customers`, passo 502): o termo vai
   * **como o operador digitou** — quem decide o que é nome e o que é CPF é o servidor —, com a
   * primeira página pequena, que é o que cabe na tela do F6 (1112).
   */
  searchCustomers(term: string): Promise<SearchCustomersOutcome>;
  /**
   * Vincula o cliente escolhido à venda aberta (`PUT /sales/{id}/customer`, passo 811b) e devolve a
   * venda inteira com o `customerId`; o nome exibido no cabeçalho é a seleção local, não a resposta.
   */
  linkCustomer(saleId: string, customerId: string): Promise<CustomerSaleOutcome>;
  /**
   * Remove o cliente da venda aberta (`DELETE /sales/{id}/customer`, passo 811b): a venda volta
   * anônima. Repetir a remoção é inofensivo no servidor (no-op com 200).
   */
  unlinkCustomer(saleId: string): Promise<CustomerSaleOutcome>;
  /**
   * Registra o pagamento na venda aberta (`POST /sales/{id}/payments`, 201, passo 905): a forma e o
   * valor vão no corpo e quem recalcula o pago, o troco e o restante é o servidor (BR-05/BR-12).
   * A `Idempotency-Key` é do chamador (1113): repetir a **mesma** tentativa (rede caiu depois de o
   * servidor gravar) com a mesma chave devolve o replay, sem um segundo pagamento.
   */
  addPayment(
    saleId: string,
    payment: SalePaymentIntent,
    idempotencyKey: string,
  ): Promise<AddPaymentOutcome>;
  /**
   * Conclui a venda aberta e paga (`POST /sales/{id}/complete`, 200, passo 907): a baixa de estoque
   * e o dinheiro no caixa são efeitos do caso de uso, na mesma transação da auditoria. A resposta é
   * a venda inteira e dela sai o resumo da tela de sucesso (BR-12).
   *
   * A `Idempotency-Key` é do chamador (1113) pelo mesmo motivo do pagamento — e mais forte: repetir
   * a conclusão com a mesma chave **não** dá uma segunda baixa de estoque nem um segundo movimento
   * de caixa; o servidor devolve o `Idempotency-Replayed` gravado.
   */
  completeSale(saleId: string, idempotencyKey: string): Promise<CompleteSaleOutcome>;
  /**
   * Sangria (F7, `POST /api/v1/cash-registers/{id}/withdrawals`, 201, passo 609): retira dinheiro da
   * sessão aberta do caixa — o `{id}` é o **caixa**, não a sessão — e devolve o movimento com o
   * esperado antes/depois da mesma transação (BR-10/BR-12). Quem exige a permissão `cash.withdrawal`
   * e recusa caixa sem sessão é o servidor.
   *
   * A `Idempotency-Key` é do chamador (1114), como no pagamento: repetir a mesma tentativa (a
   * resposta se perdeu) com a mesma chave devolve o replay, sem sangrar duas vezes.
   */
  withdrawCash(
    registerId: string,
    withdrawal: CashMovementIntent,
    idempotencyKey: string,
  ): Promise<CashMovementOutcome>;
  /**
   * Suprimento (F8, `POST /api/v1/cash-registers/{id}/supplies`, 201, passo 610): coloca dinheiro na
   * sessão aberta do caixa, no mesmo contrato da sangria — o `aboveExpected` do suprimento é sempre
   * `false`, porque suprir só aumenta o esperado.
   */
  supplyCash(
    registerId: string,
    supply: CashMovementIntent,
    idempotencyKey: string,
  ): Promise<CashMovementOutcome>;
  /**
   * Resumo do fechamento da sessão (`GET /cash-sessions/{id}/summary`, passo 612): o esperado, a
   * quebra das vendas por forma de pagamento (passo 909) e os totais por tipo de movimento
   * (sangrias e suprimentos), tudo do servidor (BR-12). Exige `cash.read`.
   */
  cashSessionSummary(sessionId: string): Promise<CashSessionSummaryOutcome>;
  /**
   * Fecha o caixa com o valor contado (`POST /cash-registers/{id}/close`, 200, passo 611) — o
   * `{id}` é o **caixa**, não a sessão — e devolve a conferência do servidor: contado, esperado e a
   * diferença dele (BR-12). Quem exige `cash.close` e recusa venda em andamento com 409
   * `SESSION_HAS_OPEN_SALES` é o servidor.
   *
   * A `Idempotency-Key` é do chamador (1115), como na gaveta: repetir a mesma tentativa com a mesma
   * chave devolve o replay, sem fechar duas vezes.
   */
  closeCashSession(
    registerId: string,
    closing: CloseCashSessionIntent,
    idempotencyKey: string,
  ): Promise<CloseCashSessionOutcome>;
  /**
   * Cancela a venda aberta (F4, `POST /sales/{id}/cancel`, 200, passo 813) com o motivo obrigatório
   * (`SaleCancelRequest`, BR-04): a venda vira `CANCELLED` no servidor e quem decide voltar à venda
   * vazia é a tela. Exige `sale.cancel` — o OPERADOR opera a venda, mas não a cancela.
   *
   * A `Idempotency-Key` é do chamador (1115): repetir a mesma tentativa devolve replay (a já
   * cancelada é no-op no servidor), sem cancelar duas vezes.
   */
  cancelSale(saleId: string, reason: string, idempotencyKey: string): Promise<CancelSaleOutcome>;
};

/** Monta a camada de API sobre um client já configurado (base URL + token da sessão). */
export function createTerminalApi(client: ApiClient): TerminalApi {
  return {
    async login(username, password, cashRegisterId) {
      try {
        // o campo só entra no corpo quando há caixa escolhido: login anônimo é o que lista os caixas
        const body: components['schemas']['LoginRequest'] = { username, password };
        if (cashRegisterId !== undefined) {
          body.cashRegisterId = cashRegisterId;
        }

        const response = await client.post<components['schemas']['LoginResponse']>(
          '/api/v1/auth/login',
          body,
        );
        const token = response.token;
        const user = response.user;

        if (token === undefined || user?.id === undefined) {
          // 200 fora do contrato: sem token não há sessão, e sem id não há operador para a tela
          return failed({ status: 0, code: null, detail: 'login sem token ou operador na resposta' });
        }

        setToken(token);
        return {
          ok: true,
          operator: { id: user.id, name: user.displayName ?? user.username ?? '' },
        };
      } catch (error) {
        return loginFailure(error);
      }
    },

    async currentSession() {
      try {
        const response = await client.get<components['schemas']['CurrentSessionResponse']>(
          '/api/v1/auth/me',
        );
        const store = response.store;

        if (store === undefined || (store.name === undefined && store.code === undefined)) {
          // sessão sem loja no contrato: o cabeçalho fica sem ela, a venda segue (1117)
          return { ok: true, store: null };
        }

        return { ok: true, store: { code: store.code ?? '', name: store.name ?? '' } };
      } catch (error) {
        // rótulo do cabeçalho: a falha não bloqueia a operação (1117)
        return { ok: false, problem: problemOf(error) };
      }
    },

    async logout() {
      try {
        await client.post<void>('/api/v1/auth/logout');
      } catch {
        // A sessão provisória é descartável: a revogação falhou, mas travar o operador por causa de
        // uma sessão que vai ser substituída seria pior. O token local sai no `finally` — sem isso
        // o login seguinte iria com o token revogado e o mecanismo bearer responderia 401 antes de
        // chegar ao recurso — e a sessão órfã expira sozinha pelo idle timeout (passo 206).
      } finally {
        clearToken();
      }
    },

    async listCashRegisters() {
      try {
        const response = await client.get<components['schemas']['CashRegisterResponse'][]>(
          '/api/v1/cash-registers',
        );
        return { ok: true, registers: toOptions(response) };
      } catch (error) {
        // na lista não há recusa de credencial: qualquer falha bloqueia
        return { ok: false, problem: problemOf(error) };
      }
    },

    async openCashRegister(registerId, openingAmount) {
      try {
        const response = await client.post<components['schemas']['CashSessionResponse']>(
          `/api/v1/cash-registers/${registerId}/open`,
          { openingAmount },
        );

        if (response.id === undefined) {
          // 201 fora do contrato: sem id de sessão a venda não tem onde acontecer
          return {
            ok: false,
            kind: 'failed',
            problem: { status: 0, code: null, detail: 'abertura sem sessão na resposta' },
          };
        }

        return { ok: true, sessionId: response.id };
      } catch (error) {
        if (
          error instanceof ApiError &&
          error.status === 409 &&
          error.code === 'CASH_REGISTER_ALREADY_OPEN'
        ) {
          // caixa já aberto não é falha: quem decide seguir com a sessão existente é a tela (1107)
          return { ok: false, kind: 'alreadyOpen' };
        }

        return { ok: false, kind: 'failed', problem: problemOf(error) };
      }
    },

    async currentCashSession(registerId) {
      try {
        const response = await client.get<components['schemas']['CurrentCashSessionResponse']>(
          `/api/v1/cash-registers/${registerId}/current-session`,
        );

        if (response.sessionId === undefined) {
          return {
            ok: false,
            problem: { status: 0, code: null, detail: 'sessão corrente sem id na resposta' },
          };
        }

        return { ok: true, sessionId: response.sessionId };
      } catch (error) {
        return { ok: false, problem: problemOf(error) };
      }
    },

    async resolveBarcode(barcode) {
      try {
        // o código vai como o leitor mandou (BR-14); o percent-encoding evita que espaço, `+` ou
        // `#` de uma etiqueta quebrem o caminho da rota
        const response = await client.get<components['schemas']['ProductBarcodeResponse']>(
          `/api/v1/products/barcode/${encodeURIComponent(barcode)}`,
        );

        return {
          ok: true,
          product: {
            id: response.id ?? '',
            name: response.name ?? '',
            price: response.price ?? 0,
            unit: response.unit ?? '',
            quantity: response.quantity ?? null,
          },
        };
      } catch (error) {
        if (error instanceof ApiError && isUnknownBarcode(error)) {
          // 404 do produto e 422 da etiqueta não são falha para quem consulta preço (1116): o termo
          // não é um código conhecido e o modal segue tratando-o como nome
          return { ok: false, kind: 'notFound', problem: problemOf(error) };
        }

        return sendFailure(error);
      }
    },

    async searchProducts(term) {
      try {
        // a busca por trecho do nome é do servidor (404): a TUI manda o termo como veio do campo
        const query = new URLSearchParams({ search: term, size: '10' });
        const response = await client.get<components['schemas']['PageResponseProductResponse']>(
          `/api/v1/products?${query.toString()}`,
        );

        return { ok: true, products: toProductOptions(response.items ?? []) };
      } catch (error) {
        if (error instanceof ApiError && error.status === 403) {
          return { ok: false, kind: 'rejected', message: 'sem permissão para consultar produtos' };
        }

        return sendFailure(error);
      }
    },

    async productStock(productId) {
      try {
        const response = await client.get<components['schemas']['StockDetailResponse']>(
          `/api/v1/stock/${productId}`,
        );

        return {
          ok: true,
          stock: {
            quantity: response.quantity ?? 0,
            minQuantity: response.minQuantity ?? 0,
            lowStock: response.lowStock ?? false,
          },
        };
      } catch (error) {
        if (error instanceof ApiError && (error.status === 403 || error.status === 404)) {
          return { ok: false, kind: 'rejected', message: stockRejectionMessage(error) };
        }

        return sendFailure(error);
      }
    },

    async createSale() {
      try {
        const response = await client.post<components['schemas']['SaleResponse']>('/api/v1/sales');

        if (response.id === undefined) {
          // 201 fora do contrato: sem id de venda não há onde incluir item
          return failed({ status: 0, code: null, detail: 'venda aberta sem id na resposta' });
        }

        // a 201 ainda não tem itens: os totais zerados são os do servidor, não uma conta da TUI
        return {
          ok: true,
          sale: {
            id: response.id,
            items: [],
            subtotal: response.subtotal ?? 0,
            discountAmount: response.discountAmount ?? 0,
            total: response.total ?? 0,
            // venda recém-criada nasce sem pagamento: o `SaleResponse` não traz o bloco (1113)
            paidAmount: 0,
            changeAmount: 0,
            payments: [],
            // a venda nasce anônima: cliente é o F6 (1112)
            customerId: null,
          },
        };
      } catch (error) {
        return sendFailure(error);
      }
    },

    async getSale(saleId) {
      try {
        const sale = toSaleView(
          await client.get<components['schemas']['SaleDetailResponse']>(`/api/v1/sales/${saleId}`),
        );

        if (sale === null) {
          return { ok: false, problem: { status: 0, code: null, detail: 'venda sem id na resposta' } };
        }

        return { ok: true, sale };
      } catch (error) {
        // leitura da reconciliação: qualquer falha bloqueia com o problema do servidor (1117)
        return { ok: false, problem: problemOf(error) };
      }
    },

    async addSaleItem(saleId, intent) {
      try {
        const response = await client.post<components['schemas']['SaleDetailResponse']>(
          `/api/v1/sales/${saleId}/items`,
          { barcode: intent.barcode, quantity: intent.quantity },
        );
        const sale = toSaleView(response);

        if (sale === null) {
          return failed({ status: 0, code: null, detail: 'item sem venda na resposta' });
        }

        return { ok: true, sale };
      } catch (error) {
        if (
          error instanceof ApiError &&
          error.status === 404 &&
          error.code === 'PRODUCT_NOT_FOUND'
        ) {
          // desfecho da operação, não falha: a tela avisa e a venda continua (1109)
          return { ok: false, kind: 'notFound', barcode: intent.barcode };
        }

        if (error instanceof ApiError && error.status === 422) {
          return {
            ok: false,
            kind: 'rejected',
            barcode: intent.barcode,
            message: rejectionMessage(error, intent.barcode),
          };
        }

        return sendFailure(error);
      }
    },

    async changeSaleItemQuantity(saleId, productId, quantity) {
      return mutateSaleItem(() =>
        client.patch<components['schemas']['SaleDetailResponse']>(
          `/api/v1/sales/${saleId}/items/${productId}`,
          { quantity },
        ),
      );
    },

    async removeSaleItem(saleId, productId) {
      return mutateSaleItem(() =>
        client.delete<components['schemas']['SaleDetailResponse']>(
          `/api/v1/sales/${saleId}/items/${productId}`,
        ),
      );
    },

    async applyDiscount(saleId, discount) {
      try {
        const response = await client.put<components['schemas']['SaleDetailResponse']>(
          `/api/v1/sales/${saleId}/discount`,
          { type: discount.type, value: discount.value, reason: discount.reason },
        );
        const sale = toSaleView(response);

        if (sale === null) {
          return failed({
            status: 0,
            code: null,
            detail: 'desconto aplicado sem venda na resposta',
          });
        }

        return { ok: true, sale };
      } catch (error) {
        if (error instanceof ApiError && isDiscountRejection(error.status)) {
          // recusa fica no modal de desconto: o operador corrige e tenta de novo ali mesmo (1111)
          return { ok: false, kind: 'rejected', message: discountRejectionMessage(error) };
        }

        return sendFailure(error);
      }
    },

    async searchCustomers(term) {
      try {
        // a busca (nome/CPF) é do servidor (502): a TUI manda o termo como veio do campo
        const query = new URLSearchParams({ search: term, page: '0', size: '10' });
        const response = await client.get<components['schemas']['PageResponseCustomerResponse']>(
          `/api/v1/customers?${query.toString()}`,
        );

        return { ok: true, customers: toCustomerOptions(response.items ?? []) };
      } catch (error) {
        if (error instanceof ApiError && error.status === 403) {
          return { ok: false, kind: 'rejected', message: 'sem permissão para consultar clientes' };
        }

        return sendFailure(error);
      }
    },

    async linkCustomer(saleId, customerId) {
      return customerSale(() =>
        client.put<components['schemas']['SaleDetailResponse']>(
          `/api/v1/sales/${saleId}/customer`,
          { customerId },
        ),
      );
    },

    async unlinkCustomer(saleId) {
      return customerSale(() =>
        client.delete<components['schemas']['SaleDetailResponse']>(
          `/api/v1/sales/${saleId}/customer`,
        ),
      );
    },

    async addPayment(saleId, payment, idempotencyKey) {
      const body: components['schemas']['SalePaymentRequest'] = {
        method: payment.method,
        amount: payment.amount,
      };
      // o recebido só existe no dinheiro (BR-05): nas demais formas o campo nem vai no corpo
      if (payment.tenderedAmount !== undefined) {
        body.tenderedAmount = payment.tenderedAmount;
      }

      try {
        const response = await client.post<components['schemas']['SaleDetailResponse']>(
          `/api/v1/sales/${saleId}/payments`,
          body,
          { idempotencyKey },
        );
        const sale = toSaleView(response);

        if (sale === null) {
          return failed({ status: 0, code: null, detail: 'pagamento sem venda na resposta' });
        }

        return { ok: true, sale };
      } catch (error) {
        if (error instanceof ApiError && isPaymentRejection(error.status)) {
          // a recusa fica na tela de pagamento: o operador corrige o valor ali mesmo (1113)
          return {
            ok: false,
            kind: 'rejected',
            message: moneyRejectionMessage(error, 'sem permissão para registrar o pagamento'),
          };
        }

        return sendFailure(error);
      }
    },

    async completeSale(saleId, idempotencyKey) {
      try {
        const response = await client.post<components['schemas']['SaleDetailResponse']>(
          `/api/v1/sales/${saleId}/complete`,
          undefined,
          { idempotencyKey },
        );

        if (response.id === undefined) {
          return failed({ status: 0, code: null, detail: 'conclusão sem venda na resposta' });
        }

        // o resumo vem do corpo tipado do complete: a tela de sucesso não calcula nada (BR-12)
        return {
          ok: true,
          receipt: {
            number: response.number ?? 0,
            total: response.total ?? 0,
            changeAmount: response.changeAmount ?? 0,
          },
        };
      } catch (error) {
        if (error instanceof ApiError && isPaymentRejection(error.status)) {
          return {
            ok: false,
            kind: 'rejected',
            message: moneyRejectionMessage(error, 'sem permissão para concluir a venda'),
          };
        }

        return sendFailure(error);
      }
    },

    async withdrawCash(registerId, withdrawal, idempotencyKey) {
      return cashMovement('withdrawal', () =>
        client.post<components['schemas']['CashMovementResponse']>(
          `/api/v1/cash-registers/${registerId}/withdrawals`,
          { amount: withdrawal.amount, reason: withdrawal.reason },
          { idempotencyKey },
        ),
      );
    },

    async supplyCash(registerId, supply, idempotencyKey) {
      return cashMovement('supply', () =>
        client.post<components['schemas']['CashMovementResponse']>(
          `/api/v1/cash-registers/${registerId}/supplies`,
          { amount: supply.amount, reason: supply.reason },
          { idempotencyKey },
        ),
      );
    },

    async cashSessionSummary(sessionId) {
      try {
        const response = await client.get<components['schemas']['CashSessionSummaryResponse']>(
          `/api/v1/cash-sessions/${sessionId}/summary`,
        );

        return { ok: true, summary: toSummaryView(response) };
      } catch (error) {
        // leitura: qualquer falha bloqueia na tela de erro, como a sessão corrente (1107)
        return { ok: false, problem: problemOf(error) };
      }
    },

    async closeCashSession(registerId, closing, idempotencyKey) {
      const body: components['schemas']['CloseCashSessionRequest'] = {
        countedAmount: closing.countedAmount,
      };
      // a observação é opcional no contrato: só vai no corpo quando o operador a informou
      if (closing.notes !== undefined) {
        body.notes = closing.notes;
      }

      try {
        const response = await client.post<components['schemas']['CashSessionDetailResponse']>(
          `/api/v1/cash-registers/${registerId}/close`,
          body,
          { idempotencyKey },
        );
        const view = toClosingView(response);

        if (view === null) {
          return failed({ status: 0, code: null, detail: 'fechamento sem conferência na resposta' });
        }

        return { ok: true, closing: view };
      } catch (error) {
        if (error instanceof ApiError && error.status < 500 && !handledByShell(error)) {
          // a recusa fica na própria tela do fechamento: o operador lê o que fazer ali mesmo (1115)
          return { ok: false, kind: 'rejected', message: closeRejectionMessage(error) };
        }

        return sendFailure(error);
      }
    },

    async cancelSale(saleId, reason, idempotencyKey) {
      try {
        const response = await client.post<components['schemas']['SaleDetailResponse']>(
          `/api/v1/sales/${saleId}/cancel`,
          { reason },
          { idempotencyKey },
        );

        if (response.id === undefined) {
          return failed({ status: 0, code: null, detail: 'cancelamento sem venda na resposta' });
        }

        return { ok: true };
      } catch (error) {
        if (
          error instanceof ApiError &&
          error.status < 500 &&
          error.status !== 404 &&
          error.status !== 409 &&
          !handledByShell(error)
        ) {
          // a recusa fica no modal do F4: 403 sem `sale.cancel` e 400 do motivo (1115)
          return { ok: false, kind: 'rejected', message: cancelSaleRejectionMessage(error) };
        }

        return sendFailure(error);
      }
    },
  };
}

/**
 * Recusa fica na tela de login com o `detail` do `problem+json` (o servidor não diz qual dos dois
 * está errado, e não é papel da TUI adivinhar); o resto é falha bloqueante. O 400 é a recusa do
 * `cashRegisterId` (caixa inativo entre a lista e a escolha): o operador corrige escolhendo outro,
 * não é falha de infraestrutura.
 */
function loginFailure(error: unknown): LoginOutcome {
  if (
    error instanceof ApiError &&
    (error.status === 400 || error.status === 401 || error.status === 423)
  ) {
    return { ok: false, kind: 'rejected', message: error.detail };
  }

  return failed(problemOf(error));
}

function failed(problem: ApiProblem): { ok: false; kind: 'failed'; problem: ApiProblem } {
  return { ok: false, kind: 'failed', problem };
}

/**
 * A falha que o mapa central (1117) manda para o shell, não para o modal: sessão caída (401) e os
 * 409 de idempotência/concorrência — que exigem releitura do servidor — nunca são recusa do
 * operador, mesmo nos fluxos que tratam todo 4xx como recusa da própria tela (gaveta, fechamento e
 * cancelamento).
 */
function handledByShell(error: ApiError): boolean {
  const kind = problemPolicy(problemOf(error)).kind;
  return kind === 'session' || kind === 'reconcile';
}

/**
 * Falha de envio: 4xx (403, 409, 400...) e contrato fora do esperado bloqueiam na tela de erro;
 * rede, timeout e 5xx são transitórias — o bipe fica guardado e o ENTER da venda refaz (1109).
 */
function sendFailure(error: unknown): SendFailure {
  if (error instanceof ApiError && error.status < 500) {
    return failed(problemOf(error));
  }

  return { ok: false, kind: 'retryable', problem: problemOf(error) };
}

/**
 * Rota de item da venda (`PATCH` da quantidade e `DELETE`, passo 1110) traduzida para a tela: a
 * venda inteira quando deu certo e o 404 `SALE_ITEM_NOT_FOUND` como desfecho próprio — o item sumiu
 * entre a leitura da tela e a ação, então a tela avisa e a venda segue como está. O resto é falha
 * bloqueante ou transitória, como no bipe (1109).
 */
async function mutateSaleItem(
  send: () => Promise<components['schemas']['SaleDetailResponse']>,
): Promise<SaleItemMutationOutcome> {
  try {
    const sale = toSaleView(await send());

    if (sale === null) {
      return failed({ status: 0, code: null, detail: 'item alterado sem venda na resposta' });
    }

    return { ok: true, sale };
  } catch (error) {
    if (error instanceof ApiError && error.status === 404 && error.code === 'SALE_ITEM_NOT_FOUND') {
      return { ok: false, kind: 'notFound' };
    }

    return sendFailure(error);
  }
}

/**
 * Código que o servidor não conhece (`GET /products/barcode/{barcode}`, passo 409): 404
 * `PRODUCT_NOT_FOUND` do produto e 422 `INVALID_INTERNAL_BARCODE` da etiqueta malformada. Os dois
 * valem como "não é um código" para a consulta de preço (1116), que segue pelo nome.
 */
function isUnknownBarcode(error: ApiError): boolean {
  return error.status === 404 || error.status === 422;
}

/** Mensagem do 422 para a tela: o operador precisa saber o que fazer, sem ver o UUID do produto. */
function rejectionMessage(error: ApiError, barcode: string): string {
  return error.code === 'PRODUCT_INACTIVE'
    ? `produto desativado no cadastro: ${barcode} — fale com o gerente`
    : `código recusado: ${error.detail}`;
}

/**
 * Recusa do desconto que fica no próprio modal (passo 1111): 400 da forma/motivo, 403 da permissão
 * `sale.discount.apply` (matriz do 810) e 422 do limite da loja (BR-04). O 404/409 não entra aqui —
 * é falha bloqueante, como nas demais operações da venda.
 */
function isDiscountRejection(status: number): boolean {
  return status === 400 || status === 403 || status === 422;
}

/**
 * Mensagem da recusa do desconto: o 403 ganha texto fixo (o OPERADOR não tem a permissão) sem
 * expor o código dela; 400 (forma/motivo) e 422 (limite da loja) já trazem o que fazer no `detail`
 * do servidor.
 */
function discountRejectionMessage(error: ApiError): string {
  return error.status === 403 ? 'sem permissão para aplicar desconto' : error.detail;
}

/**
 * Cliente da venda (`PUT`/`DELETE /sales/{id}/customer`, passo 811b) traduzido para a tela (1112): a
 * venda inteira quando deu certo e as recusas do vínculo como mensagem para o **próprio modal**. O
 * 404 `SALE_NOT_FOUND` e o 409 `SALE_NOT_OPEN` ficam de fora: a venda sumiu ou fechou, é falha
 * bloqueante como no bipe e no desconto.
 */
async function customerSale(
  send: () => Promise<components['schemas']['SaleDetailResponse']>,
): Promise<CustomerSaleOutcome> {
  try {
    const sale = toSaleView(await send());

    if (sale === null) {
      return failed({ status: 0, code: null, detail: 'cliente alterado sem venda na resposta' });
    }

    return { ok: true, sale };
  } catch (error) {
    if (error instanceof ApiError && isCustomerRejection(error)) {
      return { ok: false, kind: 'rejected', message: customerRejectionMessage(error) };
    }

    return sendFailure(error);
  }
}

/**
 * Recusa do vínculo que fica no próprio modal: 403 da permissão `sale.create` (matriz do 810), 404
 * `CUSTOMER_NOT_FOUND` do id que sumiu entre a busca e o vínculo e 422 `CUSTOMER_INACTIVE` do
 * cliente desativado no meio do caminho.
 */
function isCustomerRejection(error: ApiError): boolean {
  return error.status === 403 || error.status === 422 || error.code === 'CUSTOMER_NOT_FOUND';
}

/** Mensagem da recusa: o 403 ganha texto fixo (sem expor o código da permissão); os códigos do cliente vêm do mapa central (1117). */
function customerRejectionMessage(error: ApiError): string {
  if (error.status === 403) {
    return 'sem permissão para alterar o cliente da venda';
  }

  return problemMessage(problemOf(error)) ?? error.detail;
}

/**
 * Recusa de pagamento/conclusão que fica na própria tela (1113): 400 da forma/valor, 403 da
 * permissão (`payment.add`/`sale.complete`) e 422 do servidor. Rede/5xx e 404/409 não entram aqui —
 * são `SendFailure`, e a tela também os mostra com retry.
 */
function isPaymentRejection(status: number): boolean {
  return status === 400 || status === 403 || status === 422;
}

/**
 * Recusa de dinheiro (1113): o 403 ganha o texto fixo de quem chamou (sem expor o código da
 * permissão) e os 422 do servidor têm a mensagem do mapa central (1117); o resto mostra o `detail`.
 */
function moneyRejectionMessage(error: ApiError, forbidden: string): string {
  return error.status === 403 ? forbidden : (problemMessage(problemOf(error)) ?? error.detail);
}

/** Rótulo pt-BR do movimento, nas mensagens do modal (F7/F8). */
const MOVEMENT_LABELS: Readonly<Record<CashMovementKind, string>> = {
  withdrawal: 'sangria',
  supply: 'suprimento',
};

/**
 * Sangria/suprimento (F7/F8, passo 1114) traduzidos para a tela: o movimento gravado quando deu
 * certo e **qualquer** 4xx como recusa do próprio modal — 403 da permissão (BR-10), 400 do
 * valor/motivo e 404/409 da sessão do caixa, que mudou por fora —, com o resto em `SendFailure`
 * (rede/5xx pedem o retry manual; contrato bloqueia na tela de erro).
 */
async function cashMovement(
  kind: CashMovementKind,
  send: () => Promise<components['schemas']['CashMovementResponse']>,
): Promise<CashMovementOutcome> {
  try {
    const response = await send();

    if (response.sessionId === undefined) {
      // 201 fora do contrato: sem o movimento não há saldo esperado para a tela
      return failed({
        status: 0,
        code: null,
        detail: `${MOVEMENT_LABELS[kind]} sem movimento na resposta`,
      });
    }

    return {
      ok: true,
      movement: {
        sessionId: response.sessionId,
        type: response.type ?? '',
        amount: response.amount ?? 0,
        reason: response.reason ?? '',
        expectedBefore: response.expectedBefore ?? 0,
        expectedAfter: response.expectedAfter ?? 0,
        aboveExpected: response.aboveExpected ?? false,
      },
    };
  } catch (error) {
    if (error instanceof ApiError && error.status < 500 && !handledByShell(error)) {
      return { ok: false, kind: 'rejected', message: cashMovementRejectionMessage(error, kind) };
    }

    return sendFailure(error);
  }
}

/**
 * Mensagem da recusa que fica no modal da gaveta: o 403 ganha texto fixo (o OPERADOR não tem
 * `cash.withdrawal`/`cash.supply`, BR-10) sem expor o código da permissão; o 400 do valor/motivo e
 * os códigos da sessão de caixa vêm do mapa central (1117).
 */
function cashMovementRejectionMessage(error: ApiError, kind: CashMovementKind): string {
  if (error.status === 403) {
    return `sem permissão para registrar ${MOVEMENT_LABELS[kind]}`;
  }

  return problemMessage(problemOf(error)) ?? error.detail;
}

/**
 * `CashSessionSummaryResponse` → resumo da tela (1115): os mapas do servidor copiados como estão —
 * as quebras chegam zero-preenchidas e a TUI só exibe (BR-12).
 */
function toSummaryView(
  response: components['schemas']['CashSessionSummaryResponse'],
): CashSessionSummaryView {
  return {
    sessionId: response.sessionId ?? '',
    status: response.status ?? '',
    openingAmount: response.openingAmount ?? 0,
    expectedAmount: response.expectedAmount ?? 0,
    // nulos enquanto a sessão está aberta: o resumo do fechamento é lido antes do contado
    countedAmount: response.countedAmount ?? null,
    differenceAmount: response.differenceAmount ?? null,
    totalsByType: response.totalsByType ?? {},
    paymentsByMethod: response.paymentsByMethod ?? {},
  };
}

/** `CashSessionDetailResponse` → conferência da tela; `null` quando o servidor não mandou a conta. */
function toClosingView(
  response: components['schemas']['CashSessionDetailResponse'],
): CashClosingView | null {
  if (
    response.countedAmount === undefined ||
    response.expectedAmount === undefined ||
    response.differenceAmount === undefined
  ) {
    return null;
  }

  return {
    countedAmount: response.countedAmount,
    expectedAmount: response.expectedAmount,
    differenceAmount: response.differenceAmount,
  };
}

/**
 * Mensagem da recusa que fica na tela do fechamento: o 403 ganha texto fixo (o OPERADOR não tem
 * `cash.close`, BR-10) sem expor o código da permissão, e a venda em andamento — o único 409 que o
 * operador resolve sozinho — diz o que fazer (cancelar com o F4), pela mensagem do mapa central
 * (1117). O resto mostra o `detail` do servidor, que já é pt-BR.
 */
function closeRejectionMessage(error: ApiError): string {
  if (error.status === 403) {
    return 'sem permissão para fechar o caixa';
  }

  return problemMessage(problemOf(error)) ?? error.detail;
}

/** Mensagem da recusa do cancelamento: o 403 ganha texto fixo; o 400 do motivo diz o que corrigir. */
function cancelSaleRejectionMessage(error: ApiError): string {
  return error.status === 403 ? 'sem permissão para cancelar a venda' : error.detail;
}

/**
 * Recusa do saldo que fica no próprio modal (F2, 1116): o 403 ganha texto fixo (o OPERADOR não tem
 * `stock.read`) sem expor o código da permissão; o 404 `PRODUCT_NOT_FOUND` do produto que sumiu
 * entre a busca e o saldo diz que a consulta precisa ser refeita.
 */
function stockRejectionMessage(error: ApiError): string {
  return error.status === 403
    ? 'sem permissão para consultar o estoque'
    : 'produto não encontrado — faça a consulta de novo';
}

/** Normaliza a página de produtos para a lista do F2; registro sem `id` não é consultável e fica de fora. */
function toProductOptions(
  products: readonly components['schemas']['ProductResponse'][],
): ProductOption[] {
  const options: ProductOption[] = [];

  for (const product of products) {
    if (product.id !== undefined) {
      options.push({
        id: product.id,
        name: product.name ?? '',
        price: product.price ?? 0,
        unit: product.unit ?? '',
      });
    }
  }

  return options;
}

/** Normaliza a página de clientes para a lista do modal; registro sem `id` não é vinculável. */
function toCustomerOptions(
  customers: readonly components['schemas']['CustomerResponse'][],
): CustomerOption[] {
  const options: CustomerOption[] = [];

  for (const customer of customers) {
    if (customer.id !== undefined) {
      options.push({
        id: customer.id,
        name: customer.name ?? '',
        taxId: customer.taxId ?? null,
      });
    }
  }

  return options;
}

/** `SaleDetailResponse` → `SaleView` da tela; `null` quando a resposta não trouxe a venda. */
function toSaleView(response: components['schemas']['SaleDetailResponse']): SaleView | null {
  if (response.id === undefined) {
    return null;
  }

  return {
    id: response.id,
    items: (response.items ?? []).map((item) => ({
      productId: item.productId ?? '',
      name: item.name ?? '',
      // unidade fora do contrato cai no passo de `UN` (1109): é granularidade de entrada, não cálculo
      unit: item.unit ?? '',
      quantity: item.quantity ?? 0,
      unitPrice: item.unitPrice ?? 0,
      lineTotal: item.lineTotal ?? 0,
    })),
    subtotal: response.subtotal ?? 0,
    discountAmount: response.discountAmount ?? 0,
    total: response.total ?? 0,
    paidAmount: response.paidAmount ?? 0,
    changeAmount: response.changeAmount ?? 0,
    payments: (response.payments ?? []).map((payment) => ({
      id: payment.id ?? '',
      // forma fora do contrato cai no dinheiro (a primeira do enum): é rótulo, não cálculo
      method: payment.method ?? 'CASH',
      amount: payment.amount ?? 0,
      changeAmount: payment.changeAmount ?? 0,
      status: payment.status ?? '',
    })),
    customerId: response.customerId ?? null,
  };
}

/** Estado da API → estado da TUI: as telas só conhecem `ApiProblem` (§9.2). */
function problemOf(error: unknown): ApiProblem {
  if (error instanceof ApiError) {
    return { status: error.status, code: error.code, detail: error.detail };
  }

  // timeout, falha de rede e contrato quebrado: sem status HTTP, o texto é o que o operador pode ler
  return {
    status: 0,
    code: null,
    detail: error instanceof Error ? error.message : String(error),
  };
}

/** Normaliza a resposta para a lista da tela; registro sem `id` não é selecionável e fica de fora. */
function toOptions(registers: readonly components['schemas']['CashRegisterResponse'][]): CashRegisterOption[] {
  const options: CashRegisterOption[] = [];

  for (const register of registers) {
    if (register.id !== undefined) {
      options.push({
        id: register.id,
        code: register.code ?? '',
        name: register.name ?? '',
        open: register.status === 'OPEN',
        operatorName: register.operatorName ?? null,
      });
    }
  }

  return options;
}
