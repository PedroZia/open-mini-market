import { defineConfig } from '@playwright/test';

/**
 * E2E da retaguarda (passo 1213): navegador de verdade contra o backend de desenvolvimento.
 *
 * **Execução local, fora do CI.** O cenário grava dados no banco de dev (produto, estoque e uma
 * venda concluída) e exige o backend no ar — `quarkus:dev` na 8081 + PostgreSQL do compose —, o
 * mesmo par que a TUI usa. O `webServer` sobe o Vite da SPA (`npm run dev`), cujo proxy de `/api`
 * para 8081 já existe; um servidor já rodando na 5173 é reaproveitado. O `npm ci` do CI instala o
 * pacote, mas não baixa navegador: `npx playwright install chromium` é passo de quem roda local.
 *
 * `workers: 1` porque backend e banco são compartilhados: os specs rodam em série, sem disputar a
 * sessão do `CAIXA-01` que a TUI também usa.
 */
export default defineConfig({
  testDir: 'e2e',
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'on-first-retry',
  },
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 60_000,
  },
});
