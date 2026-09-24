import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { afterEach, describe, expect, test } from 'vitest';

import { ApiError, ApiNetworkError, ApiTimeoutError, createApiClient } from './index';

/**
 * Testes do wrapper: o servidor fake é `node:http` de verdade, numa porta efêmera, e conta as
 * requisições recebidas — é o que prova o retry (2 tentativas no GET, 1 no POST) sem mock de
 * `fetch`.
 */

/** Requisição recebida pelo servidor fake: o que o teste confere além da resposta. */
interface RecordedRequest {
  readonly method: string;
  readonly url: string;
  readonly headers: IncomingMessage['headers'];
  readonly body: string;
}

type Responder = (request: RecordedRequest, response: ServerResponse) => void;

interface FakeApi {
  readonly baseUrl: string;
  readonly requests: RecordedRequest[];
  readonly close: () => Promise<void>;
}

const PROBLEM_CONTENT_TYPE = 'application/problem+json';

/** Servidores abertos no teste; o `afterEach` fecha o que sobrou (inclusive conexão pendurada). */
const openServers = new Set<Server>();

function closeServer(server: Server): Promise<void> {
  // `closeAllConnections` primeiro: o teste de timeout deixa a conexão aberta do lado do cliente.
  server.closeAllConnections();
  return new Promise((resolve) => {
    server.close(() => resolve());
  });
}

/** Sobe o servidor fake e devolve a base URL e o diário de requisições. */
async function startFakeApi(responder: Responder): Promise<FakeApi> {
  const requests: RecordedRequest[] = [];

  const server = createServer((request, response) => {
    let body = '';
    request.on('data', (chunk: Buffer) => {
      body += chunk.toString('utf8');
    });
    request.on('end', () => {
      const recorded: RecordedRequest = {
        method: request.method ?? '',
        url: request.url ?? '',
        headers: request.headers,
        body,
      };
      requests.push(recorded);
      responder(recorded, response);
    });
  });

  openServers.add(server);
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));

  const address = server.address();
  if (address === null || typeof address === 'string') {
    throw new Error('servidor fake sem porta efêmera');
  }

  return {
    baseUrl: `http://127.0.0.1:${address.port}`,
    requests,
    close: () => closeServer(server),
  };
}

function sendJson(response: ServerResponse, status: number, payload: unknown): void {
  const body = JSON.stringify(payload);
  response.writeHead(status, {
    'content-type': 'application/json',
    'content-length': Buffer.byteLength(body),
  });
  response.end(body);
}

function sendProblem(response: ServerResponse, status: number, payload: unknown): void {
  const body = JSON.stringify(payload);
  response.writeHead(status, {
    'content-type': PROBLEM_CONTENT_TYPE,
    'content-length': Buffer.byteLength(body),
  });
  response.end(body);
}

/** Aguarda a promessa rejeitar e devolve o erro para as asserções. */
async function rejection(promise: Promise<unknown>): Promise<unknown> {
  try {
    await promise;
  } catch (error) {
    return error;
  }
  throw new Error('a chamada deveria ter falhado');
}

afterEach(async () => {
  await Promise.all([...openServers].map((server) => closeServer(server)));
  openServers.clear();
});

describe('createApiClient', () => {
  test('GET devolve o JSON, manda o bearer e não cria Idempotency-Key', async () => {
    const api = await startFakeApi((_request, response) => sendJson(response, 200, { status: 'UP' }));
    const client = createApiClient({ baseUrl: api.baseUrl, token: () => 'token-123' });

    const result = await client.get<{ status: string }>('/api/v1/meta');

    expect(result).toEqual({ status: 'UP' });
    expect(api.requests).toHaveLength(1);
    expect(api.requests[0]?.url).toBe('/api/v1/meta');
    expect(api.requests[0]?.headers.authorization).toBe('Bearer token-123');
    expect(api.requests[0]?.headers['idempotency-key']).toBeUndefined();
  });

  test('401 problem+json vira ApiError com status, code e traceId do backend', async () => {
    const api = await startFakeApi((_request, response) =>
      sendProblem(response, 401, {
        type: 'https://minimarket.local/problems/invalid-credentials',
        title: 'Credenciais inválidas',
        status: 401,
        detail: 'Usuário ou senha inválidos.',
        instance: '/api/v1/auth/me',
        code: 'INVALID_CREDENTIALS',
        traceId: 'b1f0c3a4',
      }),
    );
    const client = createApiClient({ baseUrl: api.baseUrl });

    const error = await rejection(client.get('/api/v1/auth/me'));

    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(401);
    expect(apiError.code).toBe('INVALID_CREDENTIALS');
    expect(apiError.title).toBe('Credenciais inválidas');
    expect(apiError.detail).toBe('Usuário ou senha inválidos.');
    expect(apiError.traceId).toBe('b1f0c3a4');
    expect(apiError.errors).toEqual([]);
  });

  test('409 problem+json vira ApiError com o code do conflito (o cliente decide por ele)', async () => {
    const api = await startFakeApi((_request, response) =>
      sendProblem(response, 409, {
        title: 'Caixa já aberto',
        status: 409,
        detail: 'O caixa CAIXA-01 já tem uma sessão aberta.',
        instance: '/api/v1/cash-registers/019/cursos',
        code: 'CASH_REGISTER_ALREADY_OPEN',
        traceId: 'cafe1234',
        errors: [{ field: 'cashRegisterId', message: 'já está aberto' }],
      }),
    );
    const client = createApiClient({ baseUrl: api.baseUrl });

    const error = await rejection(client.post('/api/v1/cash-registers/019/open', { openingAmount: 100 }));

    const apiError = error as ApiError;
    expect(apiError).toBeInstanceOf(ApiError);
    expect(apiError.status).toBe(409);
    expect(apiError.code).toBe('CASH_REGISTER_ALREADY_OPEN');
    expect(apiError.errors).toEqual([{ field: 'cashRegisterId', message: 'já está aberto' }]);
  });

  test('resposta de erro fora do problem+json vira ApiError com code UNKNOWN_ERROR', async () => {
    const api = await startFakeApi((_request, response) => {
      response.writeHead(502, { 'content-type': 'text/html' });
      response.end('<html>Bad Gateway</html>');
    });
    const client = createApiClient({ baseUrl: api.baseUrl, maxAttempts: 1 });

    const error = await rejection(client.post('/api/v1/sales', {}));

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe('UNKNOWN_ERROR');
    expect((error as ApiError).status).toBe(502);
  });

  test('timeout vira ApiTimeoutError e não repete a requisição', async () => {
    const api = await startFakeApi(() => {
      // Não responde de propósito: o cliente desiste no tempo máximo.
    });
    const client = createApiClient({ baseUrl: api.baseUrl, timeoutMs: 50 });

    const error = await rejection(client.get('/api/v1/products'));

    expect(error).toBeInstanceOf(ApiTimeoutError);
    expect((error as ApiTimeoutError).timeoutMs).toBe(50);
    expect(api.requests).toHaveLength(1);
  });

  test('GET repete uma vez quando o servidor devolve 5xx', async () => {
    const api = await startFakeApi((_request, response) => {
      if (api.requests.length === 1) {
        sendProblem(response, 503, { status: 503, code: 'INTERNAL_ERROR', traceId: 'x' });
        return;
      }
      sendJson(response, 200, { items: [] });
    });
    const client = createApiClient({ baseUrl: api.baseUrl });

    const result = await client.get<{ items: unknown[] }>('/api/v1/products');

    expect(result).toEqual({ items: [] });
    expect(api.requests).toHaveLength(2);
  });

  test('GET esgota as tentativas e propaga o erro da última', async () => {
    const api = await startFakeApi((_request, response) =>
      sendProblem(response, 500, { status: 500, code: 'INTERNAL_ERROR' }),
    );
    const client = createApiClient({ baseUrl: api.baseUrl, maxAttempts: 2 });

    const error = await rejection(client.get('/api/v1/products'));

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(500);
    expect(api.requests).toHaveLength(2);
  });

  test('POST não repete: o 5xx vira ApiError já na primeira tentativa', async () => {
    const api = await startFakeApi((_request, response) =>
      sendProblem(response, 503, { status: 503, code: 'INTERNAL_ERROR' }),
    );
    const client = createApiClient({ baseUrl: api.baseUrl, token: 'token-fixo' });

    const error = await rejection(client.post('/api/v1/sales', { items: [] }));

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(503);
    expect(api.requests).toHaveLength(1);
    expect(api.requests[0]?.headers.authorization).toBe('Bearer token-fixo');
  });

  test('escrita manda Idempotency-Key automática e respeita a sobrescrita', async () => {
    const api = await startFakeApi((_request, response) => sendJson(response, 201, { id: '019' }));
    const client = createApiClient({ baseUrl: api.baseUrl });

    await client.post('/api/v1/sales', { customerId: null });
    await client.put('/api/v1/sales/019/discount', { type: 'PERCENT', value: 10 }, { idempotencyKey: 'retentativa-1' });

    expect(api.requests[0]?.headers['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(api.requests[0]?.headers['idempotency-key']).not.toBe(api.requests[1]?.headers['idempotency-key']);
    expect(api.requests[1]?.headers['idempotency-key']).toBe('retentativa-1');
    expect(api.requests[0]?.headers['content-type']).toBe('application/json');
    expect(api.requests[1]?.body).toBe('{"type":"PERCENT","value":10}');
  });

  test('204 sem corpo resolve sem tentar ler JSON', async () => {
    const api = await startFakeApi((_request, response) => {
      response.writeHead(204);
      response.end();
    });
    const client = createApiClient({ baseUrl: api.baseUrl });

    const result = await client.delete('/api/v1/auth/logout');

    expect(result).toBeUndefined();
  });

  test('falha de rede vira ApiNetworkError (endereço sem servidor)', async () => {
    const api = await startFakeApi((_request, response) => sendJson(response, 200, {}));
    const baseUrl = api.baseUrl;
    await api.close();

    const error = await rejection(createApiClient({ baseUrl }).post('/api/v1/sales', {}));

    expect(error).toBeInstanceOf(ApiNetworkError);
  });
});
