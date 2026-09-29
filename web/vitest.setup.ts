import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, beforeEach, vi } from 'vitest';

// Sem `globals: true`, o RTL não registra o auto-cleanup sozinho: sem isto o DOM vaza entre testes.
afterEach(cleanup);

beforeEach(() => {
  // A suíte não fala com o backend: cada teste instala o seu stub de `fetch`. Sem stub, a chamada
  // estoura em vez de sair para a rede.
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => {
      throw new Error('fetch real desligado nos testes — instale um stub no teste');
    }),
  );
  sessionStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
});
