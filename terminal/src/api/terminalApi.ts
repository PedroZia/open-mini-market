import { ApiError, type ApiClient, type components } from '@minimarket/api-client';

import type { ApiProblem, Operator } from '../core/state';
import { setToken } from './session';

/**
 * Camada de API da TUI: o contrato do backend (`components['schemas'][...]`, gerado no
 * `@minimarket/api-client`) traduzido para o que as telas precisam — operador autenticado e caixas
 * disponíveis — com as falhas já separadas em "recusa de credencial" (a tela de login fica e avisa)
 * e "falha" (tela de erro, §11.4). Nenhuma regra de negócio aqui (BR-12): a TUI não calcula nada,
 * só transporta o que o servidor respondeu.
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

/** Resultado do login: sucesso com o operador, recusa de credencial ou falha bloqueante. */
export type LoginOutcome =
  | { ok: true; operator: Operator }
  /** 401 `INVALID_CREDENTIALS` ou 423 `ACCOUNT_LOCKED`: a mensagem fica na tela, o formulário continua. */
  | { ok: false; kind: 'rejected'; message: string }
  /** Rede, timeout, 5xx (e o resto dos 4xx): bloqueia e vai para a tela de erro. */
  | { ok: false; kind: 'failed'; problem: ApiProblem };

/** Resultado da lista de caixas: qualquer falha bloqueia. */
export type CashRegistersOutcome =
  | { ok: true; registers: CashRegisterOption[] }
  | { ok: false; problem: ApiProblem };

/** O que as telas usam da API; em teste, um dublê com esta cara. */
export type TerminalApi = {
  /**
   * Autentica no servidor e **guarda o token em memória**. Sem `cashRegisterId`: a vinculação da
   * sessão ao caixa acontece na abertura dele (passos 607/1107), não no login.
   */
  login(username: string, password: string): Promise<LoginOutcome>;
  /** Caixas ativos da loja, com o status da sessão atual e o operador dela. */
  listCashRegisters(): Promise<CashRegistersOutcome>;
};

/** Monta a camada de API sobre um client já configurado (base URL + token da sessão). */
export function createTerminalApi(client: ApiClient): TerminalApi {
  return {
    async login(username, password) {
      try {
        const response = await client.post<components['schemas']['LoginResponse']>(
          '/api/v1/auth/login',
          { username, password },
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
  };
}

/**
 * Recusa de credencial fica na tela de login com o `detail` do `problem+json` (o servidor não diz
 * qual dos dois está errado, e não é papel da TUI adivinhar); o resto é falha bloqueante.
 */
function loginFailure(error: unknown): LoginOutcome {
  if (error instanceof ApiError && (error.status === 401 || error.status === 423)) {
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
