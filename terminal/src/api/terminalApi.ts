import { ApiError, type ApiClient, type components } from '@minimarket/api-client';

import type {
  ApiProblem,
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
 * "falha" (tela de erro, §11.4). Nenhuma regra de negócio aqui (BR-12): a TUI não calcula nada, só
 * transporta o que o servidor respondeu.
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

/** Produto do bipe (passos 409 e 1104b3): o que a tela precisa para mostrar o item ou o autoteste. */
export type BarcodeProduct = {
  name: string;
  price: number;
  /** Quantidade sugerida pela etiqueta de balança; `null` fora dela (BR-14). */
  quantity: number | null;
};

/** Resultado do bipe: produto resolvido pelo servidor ou o `problem+json` da recusa (§9.2). */
export type BarcodeLookupOutcome =
  | { ok: true; product: BarcodeProduct }
  | { ok: false; problem: ApiProblem };

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

/** O que as telas usam da API; em teste, um dublê com esta cara. */
export type TerminalApi = {
  /**
   * Autentica no servidor e **guarda o token em memória**. Sem `cashRegisterId`, a sessão nasce
   * provisória (só lista caixas); com ele, nasce vinculada ao caixa (passo 607) — é o login que a
   * tela refaz depois de escolher o caixa (1107).
   */
  login(username: string, password: string, cashRegisterId?: string): Promise<LoginOutcome>;
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
   * Toda recusa (404 do produto, 422 da etiqueta, 5xx) volta como `problem+json` em `problem` — quem
   * decide o que fazer com ela é a tela (o autoteste do F11 mostra, o bipe da venda 1109 avisa).
   */
  resolveBarcode(barcode: string): Promise<BarcodeLookupOutcome>;
  /**
   * Abre a venda (`POST /sales`, 201, sem corpo) no primeiro bipe: a resposta só traz os totais
   * zerados (ainda sem itens), e é o id dela que o `addSaleItem` usa. A `Idempotency-Key` é do
   * client (1102), uma por chamada.
   */
  createSale(): Promise<CreateSaleOutcome>;
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
            name: response.name ?? '',
            price: response.price ?? 0,
            quantity: response.quantity ?? null,
          },
        };
      } catch (error) {
        return { ok: false, problem: problemOf(error) };
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

/** Mensagem da recusa: o 403 ganha texto fixo (sem expor o código da permissão); os demais já dizem o que fazer. */
function customerRejectionMessage(error: ApiError): string {
  if (error.status === 403) {
    return 'sem permissão para alterar o cliente da venda';
  }

  if (error.code === 'CUSTOMER_NOT_FOUND') {
    return 'cliente não encontrado — busque de novo';
  }

  if (error.code === 'CUSTOMER_INACTIVE') {
    return 'cliente desativado no cadastro — escolha outro';
  }

  return error.detail;
}

/**
 * Recusa de pagamento/conclusão que fica na própria tela (1113): 400 da forma/valor, 403 da
 * permissão (`payment.add`/`sale.complete`) e 422 do servidor. Rede/5xx e 404/409 não entram aqui —
 * são `SendFailure`, e a tela também os mostra com retry.
 */
function isPaymentRejection(status: number): boolean {
  return status === 400 || status === 403 || status === 422;
}

/** Mensagens dos 422 de dinheiro: o operador precisa saber o que corrigir, sem ver o código do erro. */
const PAYMENT_REJECTIONS: Readonly<Record<string, string>> = {
  PAYMENT_INSUFFICIENT: 'pagamento insuficiente — registre o valor que falta',
  PAYMENT_EXCEEDS_TOTAL: 'valor acima do que falta na venda — ajuste o valor',
  INVALID_TENDERED_AMOUNT: 'valor recebido inválido — o dinheiro precisa cobrir o valor do pagamento',
};

/**
 * Recusa de dinheiro (1113): o 403 ganha o texto fixo de quem chamou (sem expor o código da
 * permissão) e os 422 do servidor têm mensagem própria; o resto mostra o `detail` dele.
 */
function moneyRejectionMessage(error: ApiError, forbidden: string): string {
  return error.status === 403
    ? forbidden
    : (PAYMENT_REJECTIONS[error.code ?? ''] ?? error.detail);
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
