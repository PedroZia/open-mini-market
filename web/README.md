# Retaguarda web

SPA da retaguarda (Fase 12): React + Vite + TanStack Query, consumindo a API pelo client gerado
(`packages/api-client`). Em dev o app chama `/api` relativo e o Vite faz proxy para o backend na
8081 — mesma porta da TUI, sem CORS.

## Comandos

```bash
npm run dev          # Vite em http://localhost:5173 (exige o backend de dev no ar em 8081)
npm test             # Vitest (jsdom) — não precisa de backend
npm run typecheck    # tsc --noEmit
npm run lint         # ESLint
npm run build        # typecheck + build de produção
npm run test:e2e     # Playwright — E2E local, fora do CI
```

## E2E (Playwright)

O E2E roda o navegador contra o **backend de desenvolvimento** (`quarkus:dev` na 8081 + PostgreSQL
do compose) e o Vite da SPA (o `webServer` do `playwright.config.ts` sobe na 5173, reaproveitando
um servidor já aberto). Ele grava dados no banco de dev (produto, estoque e uma venda concluída) e
por isso **não roda no CI**: o `npm ci` instala o pacote, mas não baixa navegador.

```bash
npx playwright install chromium   # uma vez por máquina
npm run test:e2e                  # com o backend de dev no ar
```

Os specs vivem em `e2e/`: o login e a navegação são dirigidos pela tela; o que o operador não faz
na retaguarda (montar o cenário e limpar o produto) fala HTTP direto no `e2e/support/backend.ts`.
`workers: 1` porque backend e banco são compartilhados — a sessão do `CAIXA-01` que a TUI usa é
reaproveitada e nunca fechada pelo E2E.
