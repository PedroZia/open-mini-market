import { describe, expect, test } from 'vitest';

import { problemMessage, problemPolicy } from './problems';
import type { ApiProblem } from './state';

/**
 * Mapa central das falhas (1117): o `code` decide a política e a mensagem do operador, para que
 * shell, telas e modais respondam à mesma pergunta do mesmo jeito.
 */

const SESSION_NOTICE = 'sessão expirada — entre novamente; a venda continua aberta';

/** Falha de transporte: o que o `problemOf` monta quando o fetch nem chega a virar resposta. */
const offline: ApiProblem = { status: 0, code: null, detail: 'Falha de rede ao chamar a API.' };

describe('tratamento central das falhas (1117)', () => {
  test('todo 401 é sessão: o login volta com o aviso e a venda continua aberta', () => {
    for (const code of ['SESSION_EXPIRED', 'SESSION_IDLE_TIMEOUT']) {
      expect(problemPolicy({ status: 401, code, detail: 'sessão expirada' })).toEqual({
        kind: 'session',
        message: SESSION_NOTICE,
      });
    }
  });

  test('401 sem código conhecido também é sessão: quem responde 401 derrubou a sessão', () => {
    expect(problemPolicy({ status: 401, code: null, detail: 'não autenticado' })).toEqual({
      kind: 'session',
      message: SESSION_NOTICE,
    });
  });

  test('credencial inválida é sessão com a mensagem do formulário, não a da venda preservada', () => {
    expect(
      problemPolicy({ status: 401, code: 'INVALID_CREDENTIALS', detail: 'usuário ou senha inválidos' }),
    ).toEqual({ kind: 'session', message: 'usuário ou senha inválidos' });
  });

  test('409 de idempotência e de concorrência pedem releitura, nunca repetição às cegas', () => {
    expect(problemPolicy({ status: 409, code: 'IDEMPOTENCY_KEY_REUSED', detail: 'x' })).toEqual({
      kind: 'reconcile',
      message: 'operação já registrada com outros dados — venda conferida no servidor',
    });
    expect(problemPolicy({ status: 409, code: 'CONCURRENT_MODIFICATION', detail: 'x' })).toEqual({
      kind: 'reconcile',
      message: 'outro terminal alterou esta venda — estado conferido no servidor',
    });
  });

  test('sem resposta do servidor é retryable: é o que derruba o indicador de conexão', () => {
    expect(problemPolicy(offline)).toEqual({
      kind: 'retryable',
      message: 'sem conexão com o servidor — a operação pode ser repetida',
    });
    expect(
      problemPolicy({ status: 503, code: 'UNAVAILABLE', detail: 'servidor fora do ar' }),
    ).toMatchObject({ kind: 'retryable' });
  });

  test('4xx do operador bloqueia com a mensagem do code, sem o código do erro à vista', () => {
    expect(problemPolicy({ status: 422, code: 'PAYMENT_INSUFFICIENT', detail: 'x' })).toEqual({
      kind: 'blocking',
      message: 'pagamento insuficiente — registre o valor que falta',
    });
    expect(problemPolicy({ status: 409, code: 'SESSION_HAS_OPEN_SALES', detail: 'x' })).toEqual({
      kind: 'blocking',
      message: 'há venda em andamento — cancele a venda (F4) antes de fechar',
    });
  });

  test('code fora da tabela mostra o detail do servidor, que já é pt-BR', () => {
    expect(problemPolicy({ status: 400, code: 'WHATEVER', detail: 'valor inválido' })).toEqual({
      kind: 'blocking',
      message: 'valor inválido',
    });
    expect(problemPolicy({ status: 400, code: null, detail: 'forma inválida' })).toEqual({
      kind: 'blocking',
      message: 'forma inválida',
    });
  });

  test('problemMessage só responde pelo que a tabela conhece', () => {
    expect(problemMessage({ status: 409, code: 'SESSION_HAS_OPEN_SALES', detail: 'x' })).toBe(
      'há venda em andamento — cancele a venda (F4) antes de fechar',
    );
    expect(problemMessage({ status: 404, code: 'UNKNOWN_ERROR', detail: 'x' })).toBeNull();
    expect(problemMessage(offline)).toBeNull();
  });
});
