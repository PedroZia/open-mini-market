import { beforeEach, describe, expect, it } from 'vitest';
import { clearToken, getToken, restoreToken, storeToken, TOKEN_STORAGE_KEY } from './session';

describe('token da sessão', () => {
  beforeEach(() => {
    sessionStorage.clear();
    clearToken();
  });

  it('guarda em memória e espelha no sessionStorage', () => {
    storeToken('abc');

    expect(getToken()).toBe('abc');
    expect(sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBe('abc');
  });

  it('restoreToken relê o espelho da aba para a memória', () => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'da-aba');

    expect(restoreToken()).toBe('da-aba');
    expect(getToken()).toBe('da-aba');
  });

  it('sem espelho, restoreToken deixa a memória sem token', () => {
    storeToken('antigo');
    sessionStorage.clear();

    expect(restoreToken()).toBeNull();
    expect(getToken()).toBeNull();
  });

  it('clearToken esquece memória e espelho', () => {
    storeToken('abc');

    clearToken();

    expect(getToken()).toBeNull();
    expect(sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
  });
});
