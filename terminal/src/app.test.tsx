import { render } from 'ink-testing-library';
import { describe, expect, test, vi } from 'vitest';

import { App } from './app';

describe('App', () => {
  test('mostra o hello da fase 11', () => {
    const { lastFrame } = render(<App />);

    expect(lastFrame()).toContain('hello, PDV minimercado!');
    expect(lastFrame()).toContain('pressione q para sair');
  });

  test('encerra ao pressionar q', async () => {
    const { lastFrame, stdin } = render(<App />);

    stdin.write('q');

    // ao encerrar, o Ink limpa a tela: o frame final não traz mais o hello
    await vi.waitFor(() => {
      expect(lastFrame()).not.toContain('hello, PDV minimercado!');
    });
  });
});
