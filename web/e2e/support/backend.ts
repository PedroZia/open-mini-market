import { ApiError, createApiClient, type ApiClient, type components } from '@minimarket/api-client';

/**
 * Backend real do E2E da retaguarda (passo 1213): o que o cenário precisa antes de a UI entrar em
 * cena e a limpeza que o operador não faz pela tela.
 *
 * O fluxo de navegação é dirigido pelo Playwright com o app de verdade; aqui ficam só as operações
 * de API — achar o caixa do seed, garantir a sessão aberta, montar o produto/venda do cenário e
 * desativar o produto no fim. É o único lugar do E2E que fala HTTP fora do navegador, no mesmo
 * desenho do `terminal/e2e/backend.ts`.
 *
 * A sessão é a do ADMIN inicial do `%dev` (`admin`/`admin123`) vinculada ao `CAIXA-01`: abrir venda
 * exige sessão de caixa aberta no caixa vinculado (BR-11), e a sessão existente é **reaproveitada**
 * — a TUI usa o mesmo caixa, então nada é fechado por aqui. Venda concluída é imutável: a limpeza
 * desativa o produto e deixa a venda na trilha (BR-04).
 */

type CashRegisterResponse = components['schemas']['CashRegisterResponse'];
type CashSessionResponse = components['schemas']['CashSessionResponse'];
type CurrentCashSessionResponse = components['schemas']['CurrentCashSessionResponse'];
type LoginResponse = components['schemas']['LoginResponse'];
type PageResponseProductResponse = components['schemas']['PageResponseProductResponse'];
type PaymentResponse = components['schemas']['PaymentResponse'];
type ProductResponse = components['schemas']['ProductResponse'];
type SaleDetailResponse = components['schemas']['SaleDetailResponse'];
type SaleResponse = components['schemas']['SaleResponse'];
type StockReceiptResponse = components['schemas']['StockReceiptResponse'];

/** API do backend de dev: o Vite da SPA faz proxy de `/api` para cá (mesma porta da TUI). */
export const API_URL = 'http://localhost:8081';

export class Backend {
  private readonly client: ApiClient;

  private token: string | null = null;

  constructor(baseUrl: string = API_URL) {
    this.client = createApiClient({ baseUrl, token: () => this.token });
  }

  /** Autentica e passa a usar a sessão nova; com `cashRegisterId`, ela nasce vinculada ao caixa. */
  async signIn(username: string, password: string, cashRegisterId?: string): Promise<void> {
    const body: components['schemas']['LoginRequest'] = { username, password };
    if (cashRegisterId !== undefined) {
      body.cashRegisterId = cashRegisterId;
    }

    const response = await this.client.post<LoginResponse>('/api/v1/auth/login', body);

    if (response.token === undefined) {
      throw new Error('login do E2E sem token na resposta do backend');
    }

    this.token = response.token;
  }

  /** Revoga a sessão do fixture: o login de reconhecimento não vira sessão órfã no banco de dev. */
  async signOut(): Promise<void> {
    try {
      await this.client.post<void>('/api/v1/auth/logout');
    } finally {
      this.token = null;
    }
  }

  /** Caixa ativo pelo código (`CAIXA-01`): é o caixa do seed de dev que a TUI usa. */
  async requireRegister(code: string): Promise<CashRegisterResponse> {
    const registers = await this.client.get<CashRegisterResponse[]>('/api/v1/cash-registers');
    const register = registers.find((candidate) => candidate.code === code);

    if (register?.id === undefined) {
      throw new Error(`caixa ${code} não existe no backend de desenvolvimento`);
    }

    return register;
  }

  /** Sessão aberta do caixa; `null` quando não há (`404 CASH_SESSION_NOT_OPEN`). */
  async currentSession(registerId: string): Promise<string | null> {
    try {
      const response = await this.client.get<CurrentCashSessionResponse>(
        `/api/v1/cash-registers/${registerId}/current-session`,
      );

      return response.sessionId ?? null;
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        return null;
      }

      throw error;
    }
  }

  /** Abre a sessão do caixa (só quando não há): fundo de troco zero — o E2E não mexe em dinheiro. */
  async openSession(registerId: string, openingAmount: number): Promise<string> {
    const session = await this.client.post<CashSessionResponse>(
      `/api/v1/cash-registers/${registerId}/open`,
      { openingAmount },
    );

    if (session.id === undefined) {
      throw new Error('sessão do E2E aberta sem id na resposta');
    }

    return session.id;
  }

  /** Produto do cenário: barcode único e preço conhecido, para a conferência na tela ser exata. */
  async createProduct(name: string, barcode: string, price: number): Promise<string> {
    const product = await this.client.post<ProductResponse>('/api/v1/products', {
      name,
      barcode,
      unit: 'UN',
      price,
    });

    if (product.id === undefined) {
      throw new Error('produto do E2E criado sem id na resposta');
    }

    return product.id;
  }

  /** Entrada de mercadoria (passo 706): o saldo do cenário nasce de um movimento real do ledger. */
  async receiveStock(
    productId: string,
    quantity: number,
    reason: string,
  ): Promise<StockReceiptResponse> {
    return this.client.post<StockReceiptResponse>(`/api/v1/stock/${productId}/receipts`, {
      quantity,
      reason,
    });
  }

  /** Abre a venda na sessão de caixa do login (a rota exige a sessão vinculada e aberta). */
  async openSale(): Promise<SaleResponse> {
    const sale = await this.client.post<SaleResponse>('/api/v1/sales');

    if (sale.id === undefined) {
      throw new Error('venda do E2E aberta sem id na resposta');
    }

    return sale;
  }

  /** Item pelo **barcode bruto**: quem resolve o código é o servidor (BR-10), nunca o teste. */
  async addItemByBarcode(
    saleId: string,
    barcode: string,
    quantity: number,
  ): Promise<SaleDetailResponse> {
    return this.client.post<SaleDetailResponse>(`/api/v1/sales/${saleId}/items`, {
      barcode,
      quantity,
    });
  }

  /** Pagamento em dinheiro: o troco é calculado pelo servidor (BR-05), não pelo teste. */
  async payCash(
    saleId: string,
    amount: number,
    tenderedAmount: number,
  ): Promise<PaymentResponse> {
    return this.client.post<PaymentResponse>(`/api/v1/sales/${saleId}/payments`, {
      method: 'CASH',
      amount,
      tenderedAmount,
    });
  }

  /** Conclui a venda: baixa o estoque no ledger e grava a trilha na mesma transação (BR-13). */
  async completeSale(saleId: string): Promise<SaleDetailResponse> {
    return this.client.post<SaleDetailResponse>(`/api/v1/sales/${saleId}/complete`);
  }

  /**
   * Produto vivo pelo **nome** (o `search` de `GET /products` cobre o nome, não o barcode);
   * `null` quando não existe. Usado pela limpeza do cenário criado pela UI.
   */
  async findProductId(name: string): Promise<string | null> {
    const query = new URLSearchParams({ search: name, size: '100' });
    const page = await this.client.get<PageResponseProductResponse>(
      `/api/v1/products?${query.toString()}`,
    );
    const product = page.items?.find((candidate) => candidate.name === name);

    return product?.id ?? null;
  }

  /** Limpeza do cenário: soft delete do produto criado (venda concluída não se apaga). */
  async disableProduct(productId: string): Promise<void> {
    await this.client.post<ProductResponse>(`/api/v1/products/${productId}/disable`);
  }
}

/** Código de barras único do cenário: prefixo de GTIN do Brasil + dígitos aleatórios. */
export function randomBarcode(): string {
  const digits = Array.from({ length: 10 }, () => Math.floor(Math.random() * 10)).join('');

  return `789${digits}`;
}

/** Nome único por execução, para a busca da lista não colidir com produto de execução anterior. */
export function uniqueName(prefix: string): string {
  return `${prefix} ${Date.now()} ${Math.floor(Math.random() * 1000)}`;
}
