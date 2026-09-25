import { problemPolicy } from '../core/problems';
import type { ApiProblem } from '../core/state';
import type { TerminalApi } from './terminalApi';

/**
 * Guarda das chamadas da API (1117): o shell embrulha a instância única e passa a embrulhada para
 * as telas, então **toda** chamada — inclusive as que a tela trata sozinha, como o rodapé do
 * pagamento — passa pelo tratamento central antes de virar tela:
 *
 * - 401 (`session`): avisa o shell, que leva ao login preservando a venda aberta;
 * - 409 de idempotência/concorrência (`reconcile`): avisa o shell, que relê a venda do servidor
 *   antes de qualquer repetição — a operação **não** é repetida às cegas;
 * - conexão: toda resposta do servidor é `online`; falha de transporte (`status` 0, rede ou
 *   timeout) é `offline` — é o que alimenta o indicador da barra de status.
 *
 * A guarda **não muda o desfecho**: a tela recebe exatamente o que a camada de API devolveu e
 * decide o que sempre decidiu. O que ela acrescenta são os fatos que nenhuma tela enxerga sozinha.
 */

/** O que o shell escuta da guarda: as três decisões centrais do 1117, com a mensagem já resolvida. */
export type ProblemGuardHandlers = {
  /** Sessão caiu (401): o shell leva ao login com o aviso, sem descartar a venda (1117). */
  onSession(message: string): void;
  /**
   * 409 de idempotência/concorrência: o shell relê a venda do servidor (`saleId` quando houver) e
   * mostra o aviso da conferência (1117).
   */
  onReconcile(saleId: string | null, message: string): void;
  /** Conexão observada: `false` na falha de transporte, `true` a cada resposta do servidor. */
  onConnection(online: boolean): void;
};

/** Métodos cujo primeiro argumento é o id da venda: é deles que sai a releitura da reconciliação. */
const SALE_METHODS: ReadonlySet<string> = new Set([
  'addSaleItem',
  'changeSaleItemQuantity',
  'removeSaleItem',
  'applyDiscount',
  'linkCustomer',
  'unlinkCustomer',
  'addPayment',
  'completeSale',
  'cancelSale',
]);

/**
 * Desfecho comum das chamadas: sucesso, ou a falha com o problema que a política classifica. Os
 * tipos de cada método são mais ricos (recusas, `notFound`…), mas todos têm `ok` e os problemas têm
 * `problem` — é o que a guarda precisa ler, sem reinterpretar o contrato de ninguém.
 */
type Outcome = { ok: boolean; problem?: ApiProblem };

/** Devolve a API embrulhada: mesma cara, com os fatos de sessão, reconciliação e conexão observados. */
export function withProblemGuard(api: TerminalApi, handlers: ProblemGuardHandlers): TerminalApi {
  return new Proxy(api, {
    get(target, property, receiver) {
      const original: unknown = Reflect.get(target, property, receiver);

      if (typeof property !== 'string' || typeof original !== 'function') {
        return original;
      }

      return async (...args: unknown[]) => {
        const call = original as (...callArgs: unknown[]) => Promise<unknown>;
        const outcome = await call.apply(target, args);

        observe(property, args, outcome, handlers);

        return outcome;
      };
    },
  }) as TerminalApi;
}

/** Classifica o desfecho e entrega à guarda: sessão, reconciliação e conexão (1117). */
function observe(
  method: string,
  args: unknown[],
  outcome: unknown,
  handlers: ProblemGuardHandlers,
): void {
  const problem = problemOf(outcome);

  if (problem === undefined) {
    handlers.onConnection(true);
    return;
  }

  // resposta com status é servidor de pé, mesmo recusando; sem status, a resposta não chegou
  handlers.onConnection(problem.status > 0);

  const policy = problemPolicy(problem);

  if (policy.kind === 'session') {
    handlers.onSession(policy.message);
    return;
  }

  if (policy.kind === 'reconcile') {
    handlers.onReconcile(saleIdOf(method, args), policy.message);
  }
}

/** O problema do desfecho, quando ele é um objeto de falha: `logout` não devolve desfecho nenhum. */
function problemOf(outcome: unknown): ApiProblem | undefined {
  return typeof outcome === 'object' && outcome !== null ? (outcome as Outcome).problem : undefined;
}

/** A venda que a chamada mirou, quando ela é de venda: fora disso não há estado a reconciliar. */
function saleIdOf(method: string, args: unknown[]): string | null {
  if (!SALE_METHODS.has(method)) {
    return null;
  }

  const saleId = args[0];
  return typeof saleId === 'string' ? saleId : null;
}
