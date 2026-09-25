import { ApiError, type ApiClient, type components } from '@minimarket/api-client';

import type { ApiProblem, Operator } from '../core/state';
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
