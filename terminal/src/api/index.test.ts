import { describe, expect, test } from 'vitest';

import { resolveApiUrl } from './index';

/**
 * Base da API do terminal (passo 1119): `MINIMARKET_API_URL` manda; sem a variável, o default de
 * desenvolvimento é o backend na 8081 — a porta publicada pelo compose (`APP_PORT`) e usada nas
 * instruções de `terminal/README.md`.
 */
describe('resolveApiUrl', () => {
  test('MINIMARKET_API_URL definida manda na base', () => {
    expect(resolveApiUrl({ MINIMARKET_API_URL: 'http://192.168.0.10:9090' })).toBe(
      'http://192.168.0.10:9090',
    );
  });

  test('sem a variável, a base é a API local de desenvolvimento (8081)', () => {
    expect(resolveApiUrl({})).toBe('http://localhost:8081');
  });
});
