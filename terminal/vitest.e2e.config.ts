import { defineConfig } from 'vitest/config';

/**
 * E2E do PDV (passo 1120): um fluxo de operador por vez contra o **backend real** apontado por
 * `MINIMARKET_API_URL` (default `http://localhost:8081`) — sem dublê de API e sem banco de mentira.
 *
 * O tempo é generoso de propósito (rede, Argon2id do login e a transação de cada operação) e não há
 * paralelismo: o cenário compartilha o caixa, o estoque e a sessão do banco de desenvolvimento.
 */
export default defineConfig({
  test: {
    include: ['e2e/**/*.e2e.test.{ts,tsx}'],
    fileParallelism: false,
    testTimeout: 120_000,
    hookTimeout: 120_000,
  },
});
