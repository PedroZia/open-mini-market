import type { components } from '@minimarket/api-client';
import { describe, expect, it } from 'vitest';
import { normalizePage, sortParam } from './page';

describe('normalizePage', () => {
  it('preenche os campos que o contrato deixa opcionais', () => {
    expect(normalizePage(undefined)).toEqual({
      items: [],
      page: 0,
      size: 0,
      totalItems: 0,
      totalPages: 0,
    });
  });

  it('aceita o tipo do contrato e mantém o que o servidor mandou', () => {
    const contractPage: components['schemas']['PageResponseProductResponse'] = {
      items: [],
      page: 1,
      size: 20,
      totalItems: 42,
      totalPages: 3,
    };

    expect(normalizePage(contractPage)).toEqual({
      items: [],
      page: 1,
      size: 20,
      totalItems: 42,
      totalPages: 3,
    });
  });

  it('copia os itens para a tela não mutar o cache', () => {
    const items = [{ id: 'p1' }];
    const page = normalizePage({ items });

    expect(page.items).toEqual(items);
    expect(page.items).not.toBe(items);
  });

  it('sem os totais, assume uma página com o que veio', () => {
    expect(normalizePage({})).toEqual({
      items: [],
      page: 0,
      size: 0,
      totalItems: 0,
      totalPages: 0,
    });
    expect(normalizePage({ items: ['arroz'] })).toEqual({
      items: ['arroz'],
      page: 0,
      size: 1,
      totalItems: 1,
      totalPages: 1,
    });
  });
});

describe('sortParam', () => {
  it('monta o `sort=campo,asc|desc` do contrato', () => {
    expect(sortParam({ field: 'name', direction: 'asc' })).toBe('name,asc');
    expect(sortParam({ field: 'createdat', direction: 'desc' })).toBe('createdat,desc');
  });

  it('sem ordenação, não manda o parâmetro', () => {
    expect(sortParam(null)).toBeNull();
    expect(sortParam(undefined)).toBeNull();
  });
});
