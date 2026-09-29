import type { components } from '@minimarket/api-client';
import { api } from '../../../api/client';

/** Tipos do contrato OpenAPI — nada escrito à mão (§9.3). */
export type CashRegisterResponse = components['schemas']['CashRegisterResponse'];
export type CashSessionStatus = components['schemas']['CashSessionStatus'];
export type CurrentCashSessionResponse = components['schemas']['CurrentCashSessionResponse'];
export type CashSessionDetailResponse = components['schemas']['CashSessionDetailResponse'];
export type CashSessionSummaryResponse = components['schemas']['CashSessionSummaryResponse'];
export type CashMovementRequest = components['schemas']['CashMovementRequest'];
export type CashMovementResponse = components['schemas']['CashMovementResponse'];
export type CloseCashSessionRequest = components['schemas']['CloseCashSessionRequest'];
export type CashMovementType = components['schemas']['CashMovementType'];
export type PaymentMethod = components['schemas']['PaymentMethod'];

const REGISTERS_PATH = '/api/v1/cash-registers';
const SESSIONS_PATH = '/api/v1/cash-sessions';

/**
 * Caixas da loja (`GET /cash-registers`, passo 602): **array sem paginação** — o §9.3 não define
 * `page`/`size` para esta rota e são poucos caixas por loja; a ordem por código vem do servidor.
 * Exige `cash.read`: a tela só chama com a permissão na sessão.
 */
export function listCashRegisters(): Promise<CashRegisterResponse[]> {
  return api.get<CashRegisterResponse[]>(REGISTERS_PATH);
}

/**
 * Sessão aberta do caixa (`GET /cash-registers/{id}/current-session`, passo 608) com o esperado
 * recalculado pelo servidor (BR-12) e os totais por tipo de movimento. Caixa sem sessão aberta —
 * inclusive id desconhecido — é 404 `CASH_SESSION_NOT_OPEN`: é o caixa fechado, não uma falha.
 */
export function getCurrentCashSession(
  cashRegisterId: string,
): Promise<CurrentCashSessionResponse> {
  return api.get<CurrentCashSessionResponse>(
    `${REGISTERS_PATH}/${cashRegisterId}/current-session`,
  );
}

/**
 * Detalhe da sessão (`GET /cash-sessions/{id}`, passo 612): abertura e, quando fechada, a
 * conferência. Id desconhecido é 404 `CASH_SESSION_NOT_FOUND`.
 */
export function getCashSession(sessionId: string): Promise<CashSessionDetailResponse> {
  return api.get<CashSessionDetailResponse>(`${SESSIONS_PATH}/${sessionId}`);
}

/**
 * Resumo do fechamento (`GET /cash-sessions/{id}/summary`, passo 612): esperado × contado com os
 * totais por tipo de movimento e a quebra das vendas por forma de pagamento (passo 909). Os dois
 * números de dinheiro são do servidor — a tela não recalcula nada (BR-12).
 */
export function getCashSessionSummary(sessionId: string): Promise<CashSessionSummaryResponse> {
  return api.get<CashSessionSummaryResponse>(`${SESSIONS_PATH}/${sessionId}/summary`);
}

/**
 * Sangria (`POST /cash-registers/{id}/withdrawals`, passo 609): retira dinheiro da sessão aberta com
 * motivo obrigatório e devolve o movimento gravado. Exige `cash.withdrawal` no servidor e é operação
 * de dinheiro idempotente por contrato (§8) — o client manda a `Idempotency-Key` sozinho.
 *
 * Caixa sem sessão aberta é 404 `CASH_SESSION_NOT_OPEN`. O `aboveExpected` do corpo é do servidor
 * (BR-12): quando `true`, a sangria passou do esperado — o movimento **foi registrado** e a tela
 * alerta o operador, nunca trata como erro.
 */
export function recordWithdrawal(
  cashRegisterId: string,
  body: CashMovementRequest,
): Promise<CashMovementResponse> {
  return api.post<CashMovementResponse>(`${REGISTERS_PATH}/${cashRegisterId}/withdrawals`, body);
}

/**
 * Suprimento (`POST /cash-registers/{id}/supplies`, passo 610): coloca dinheiro na sessão aberta,
 * com a mesma forma e o mesmo contrato de idempotência da sangria, mas exigindo `cash.supply`.
 * O `aboveExpected` do corpo é sempre `false` — o suprimento só aumenta o esperado.
 */
export function recordSupply(
  cashRegisterId: string,
  body: CashMovementRequest,
): Promise<CashMovementResponse> {
  return api.post<CashMovementResponse>(`${REGISTERS_PATH}/${cashRegisterId}/supplies`, body);
}

/**
 * Fechamento (`POST /cash-registers/{id}/close`, passos 611/612): confere o valor contado — o
 * esperado e a diferença são do servidor (BR-12) — e devolve 200 com a sessão fechada. Exige
 * `cash.close` e é idempotente pela mesma chave (§8). Caixa sem sessão aberta ou já fechado é 409
 * `CASH_SESSION_ALREADY_CLOSED` e venda `OPEN` na sessão é 409 `SESSION_HAS_OPEN_SALES`.
 */
export function closeCashSession(
  cashRegisterId: string,
  body: CloseCashSessionRequest,
): Promise<CashSessionDetailResponse> {
  return api.post<CashSessionDetailResponse>(`${REGISTERS_PATH}/${cashRegisterId}/close`, body);
}
