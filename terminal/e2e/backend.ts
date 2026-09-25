import { ApiError, createApiClient, type ApiClient, type components } from '@minimarket/api-client';

/**
 * Backend real do E2E do PDV (passo 1120): as fixtures e as conferências que não passam pela TUI.
 *
 * O fluxo do operador é dirigido pela UI, com a `terminalApi` real (o mesmo client que o app usa);
 * aqui ficam só as operações que o operador não faz pela tela — criar o produto do cenário, dar
 * entrada de estoque, limpar a sessão de caixa que uma execução anterior deixou aberta e conferir
 * saldo, auditoria e sessão depois do fluxo. É o único lugar do E2E que fala HTTP fora da TUI.
 *
 * A sessão daqui é a do ADMIN inicial do `%dev` (`admin`/`admin123`), com o caixa vinculado no
 * login: cancelar venda e fechar caixa conferem a posse pelo caixa da sessão (BR-11), então a
 * sessão de limpeza precisa estar no mesmo caixa que a TUI vai usar.
 */

type AuditEventResponse = components['schemas']['AuditEventResponse'];
type CashRegisterResponse = components['schemas']['CashRegisterResponse'];
type CashSessionDetailResponse = components['schemas']['CashSessionDetailResponse'];
type CashSessionSummaryResponse = components['schemas']['CashSessionSummaryResponse'];
type ProductResponse = components['schemas']['ProductResponse'];
type SaleSummaryResponse = components['schemas']['SaleSummaryResponse'];
type StockDetailResponse = components['schemas']['StockDetailResponse'];
type StockReceiptResponse = components['schemas']['StockReceiptResponse'];

/** Status de venda do contrato, para o filtro do histórico (`GET /sales`). */
type SaleStatusFilter = components['schemas']['SaleStatus'];

export class Backend {
  private readonly client: ApiClient;

  private token: string | null = null;

  constructor(baseUrl: string) {
    this.client = createApiClient({ baseUrl, token: () => this.token });
  }

  /** Autentica e passa a usar a sessão nova; com `cashRegisterId`, ela nasce vinculada ao caixa. */
  async signIn(username: string, password: string, cashRegisterId?: string): Promise<void> {
    const body: components['schemas']['LoginRequest'] = { username, password };
    if (cashRegisterId !== undefined) {
      body.cashRegisterId = cashRegisterId;
    }

    const response = await this.client.post<components['schemas']['LoginResponse']>(
      '/api/v1/auth/login',
      body,
    );

    if (response.token === undefined) {
      throw new Error('login do E2E sem token na resposta do backend');
    }

    this.token = response.token;
  }

  /**
   * Revoga a sessão do fixture. O login de reconhecimento (para achar o caixa) não é o que opera a
   * limpeza, então ele sai de cena aqui em vez de virar sessão órfã no banco de dev.
   */
  async signOut(): Promise<void> {
    try {
      await this.client.post<void>('/api/v1/auth/logout');
    } finally {
      this.token = null;
    }
  }

  /** Caixa ativo pelo código (`CAIXA-01`): é o que o operador escolhe na tela de login (1106). */
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
      const response = await this.client.get<components['schemas']['CurrentCashSessionResponse']>(
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

  /** Vendas de um status na sessão (o histórico exige `report.read`, que o ADMIN tem). */
  async salesOf(sessionId: string, status: SaleStatusFilter): Promise<SaleSummaryResponse[]> {
    const query = new URLSearchParams({ cashSessionId: sessionId, status, size: '100' });
    const page = await this.client.get<components['schemas']['PageResponseSaleSummaryResponse']>(
      `/api/v1/sales?${query.toString()}`,
    );

    return page.items ?? [];
  }

  /** Cancela a venda aberta com o motivo obrigatório (BR-04) — a limpeza da execução anterior. */
  async cancelSale(saleId: string, reason: string): Promise<void> {
    await this.client.post<components['schemas']['SaleDetailResponse']>(
      `/api/v1/sales/${saleId}/cancel`,
      { reason },
    );
  }

  /** Resumo da sessão: o esperado e as quebras são do servidor (BR-12). */
  async summary(sessionId: string): Promise<CashSessionSummaryResponse> {
    return this.client.get<CashSessionSummaryResponse>(
      `/api/v1/cash-sessions/${sessionId}/summary`,
    );
  }

  /** Fecha a sessão com o contado; o esperado e a diferença continuam sendo do servidor (BR-12). */
  async closeSession(registerId: string, countedAmount: number): Promise<CashSessionDetailResponse> {
    return this.client.post<CashSessionDetailResponse>(
      `/api/v1/cash-registers/${registerId}/close`,
      { countedAmount },
    );
  }

  /** Detalhe da sessão: é onde o contado/esperado/diferença do fechamento ficam gravados. */
  async session(sessionId: string): Promise<CashSessionDetailResponse> {
    return this.client.get<CashSessionDetailResponse>(`/api/v1/cash-sessions/${sessionId}`);
  }

  /** Eventos de auditoria da sessão de caixa, do mais antigo para o mais novo. */
  async auditEvents(cashSessionId: string): Promise<AuditEventResponse[]> {
    const query = new URLSearchParams({
      cashSessionId,
      size: '100',
      sort: 'occurredat,asc',
    });
    const page = await this.client.get<components['schemas']['PageResponseAuditEventResponse']>(
      `/api/v1/audit-events?${query.toString()}`,
    );

    return page.items ?? [];
  }

  /** Produto do cenário: barcode único e preço conhecido, para a baixa ser uma conta do teste. */
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

  /** Saldo do produto: a baixa da venda concluída aparece aqui (BR-13). */
  async stock(productId: string): Promise<StockDetailResponse> {
    return this.client.get<StockDetailResponse>(`/api/v1/stock/${productId}`);
  }
}

/** Código de barras único do cenário: prefixo de GTIN do Brasil + dígitos aleatórios. */
export function randomBarcode(): string {
  const digits = Array.from({ length: 10 }, () => Math.floor(Math.random() * 10)).join('');

  return `789${digits}`;
}
