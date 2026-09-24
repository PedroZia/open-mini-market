# @minimarket/api-client

Tipos do contrato OpenAPI do backend **e** o wrapper `fetch` com o que a API do PDV exige: bearer
token, `Idempotency-Key` automática nas escritas, timeout, retry **só de leitura** e tradução de
`problem+json` para `ApiError`.

## Tipos: `npm run generate`

`src/schema.d.ts` (commitado) é gerado do contrato do backend — a documentação viva de
`/q/openapi` (§9.1 do plano) é a **fonte única** dos tipos; o spec em si não vai para o
repositório. Com o backend de pé:

```bash
npm run generate                      # default: http://localhost:8080/q/openapi?format=json
OPENAPI_URL="http://localhost:8081/q/openapi?format=json" npm run generate   # outra instância
```

O script é `scripts/generate.mjs` (`openapi-typescript`); rode-o depois de qualquer mudança de
contrato no backend — o diff de `src/schema.d.ts` aparece na revisão.

Os tipos ficam em `paths`, `components` e `operations`, no formato do `openapi-typescript`:

```ts
import type { components, paths } from '@minimarket/api-client';

type Me = components['schemas']['MeResponse'];
type LoginBody = paths['/api/v1/auth/login']['post']['requestBody']['content']['application/json'];
```

> Parâmetros de query (`page`, `size`, ...) vêm como `string` no contrato: o client monta a URL
> como texto. Se algum dia atrapalhar, o ajuste é no backend (`@Schema(type = INTEGER)` no
> `@QueryParam`), nunca no tipo gerado à mão.

## Client

```ts
import { createApiClient, type components } from '@minimarket/api-client';

const client = createApiClient({
  baseUrl: 'http://localhost:8080',
  // o token só existe depois do login: o provedor é lido a cada requisição
  token: () => session.token,
});

const me = await client.get<components['schemas']['CurrentSessionResponse']>('/api/v1/auth/me');
const page = await client.get<components['schemas']['PageResponseProductResponse']>(
  '/api/v1/products?page=0&size=20',
);
```

> Os endpoints que devolvem `Response` no JAX-RS (criações com `Location`, abertura/fechamento de
> caixa, sangria, suprimento) aparecem com o corpo `unknown` no contrato — anotá-los com
> `@APIResponse` é assunto de backend, não deste pacote.

| Opção | Comportamento |
| --- | --- |
| `token` | `Authorization: Bearer ...`; string fixa ou provedor (sync/async); vazio ⇒ requisição anônima (login, `/meta`) |
| `timeoutMs` | tempo máximo **por tentativa** (`AbortSignal.timeout`); default 10 s ⇒ `ApiTimeoutError` |
| `maxAttempts` | tentativas totais de um **GET** (a 1ª + retry); default 2; `1` desliga o retry |
| `fetch` | injeção de `fetch` para testes/instrumentação |

- **`Idempotency-Key`** é gerada (`crypto.randomUUID()`) em POST/PUT/PATCH/DELETE; para repetir a
  **mesma** operação (duplo clique, resposta perdida) passe a chave da 1ª chamada:
  `client.post(path, body, { idempotencyKey })` — o servidor devolve a mesma resposta, sem duplicar
  venda/movimento (§8 do plano).
- **Retry** acontece só em GET, e só em falha de rede ou 5xx: repetir escrita duplicaria dinheiro e
  estoque. Timeout **não** repete (a espera já foi gasta uma vez).
- **Erros**: 4xx/5xx viram `ApiError` (`status`, `code`, `title`, `detail`, `errors[]`, `traceId`);
  o `code` é estável e é o que a lógica do cliente usa (`INVALID_CREDENTIALS`, `CASH_REGISTER_ALREADY_OPEN`,
  `INSUFFICIENT_STOCK`, ...). Fora do padrão (`problem+json` ausente) o `code` é `UNKNOWN_ERROR`.
- `204 No Content` resolve com `undefined`.

## Comandos

```bash
npm test          # vitest (servidor HTTP fake: retry, timeout, problem+json)
npm run typecheck # tsc --noEmit
npm run generate  # regenera src/schema.d.ts do /q/openapi
```
