import { createRequire } from 'node:module';
import path from 'node:path';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

const require = createRequire(import.meta.url);
/** `main` do pacote (`dist/development/index.js`); os builds ESM moram no mesmo diretório. */
const reactRouterDist = path.dirname(require.resolve('react-router'));

/**
 * Suíte do web: componentes/rotas em jsdom, com o setup de matchers do jest-dom.
 *
 * O alias fixa o react-router nos builds ESM — os mesmos que o navegador carrega via Vite. Fora do
 * bundler, o pacote resolve `react-router` para o CJS (`default`) e `react-router/dom` para o ESM:
 * as duas portas viram **duas cópias** do pacote e o `NavLink` deixa de achar o contexto do
 * `RouterProvider` ("useLocation() may be used only in the context of a <Router> component").
 */
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./vitest.setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    alias: [
      { find: /^react-router\/dom$/, replacement: path.join(reactRouterDist, 'dom-export.mjs') },
      { find: /^react-router$/, replacement: path.join(reactRouterDist, 'index.mjs') },
    ],
  },
});
