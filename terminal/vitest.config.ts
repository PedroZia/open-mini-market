import { defineConfig } from 'vitest/config';

/**
 * Suíte padrão da TUI (`src/`): unidade e integração das telas e do núcleo, com a API dublada.
 *
 * O E2E do fluxo completo (`e2e/`, passo 1120) é dirigido por `vitest.e2e.config.ts` e roda com
 * `npm run test:e2e`: ele exige um backend real no ar e é execução local obrigatória, então fica
 * **fora** do `npm test`.
 */
export default defineConfig({
  test: {
    include: ['src/**/*.test.{ts,tsx}'],
    // `src/opentui/**` é a UI nova (Fase 11b) e só roda no `bun test` (`npm run test:opentui`):
    // o Vitest/Node não carrega a lib nativa da OpenTUI (decisão do spike 1121).
    exclude: ['e2e/**', 'src/opentui/**'],
  },
});
