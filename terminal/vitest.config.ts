import { defineConfig } from 'vitest/config';

/**
 * Suíte do núcleo e da API da TUI (`src/`, menos `src/opentui/`): unidade e integração com a API
 * dublada, rodando no Vitest/Node.
 *
 * A UI (`src/opentui/**`) roda no `bun test` — o Vitest/Node não carrega a lib nativa da OpenTUI
 * (decisão do spike 1121) —; o `npm test` encadeia as duas suítes. O E2E (`e2e/`, passo 1130a) fica
 * de fora: exige o backend real no ar e roda com `bun test e2e`.
 */
export default defineConfig({
  test: {
    include: ['src/**/*.test.{ts,tsx}'],
    exclude: ['e2e/**', 'src/opentui/**'],
  },
});
