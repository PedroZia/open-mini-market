import { describe, expect, it } from 'vitest';
import { displayKey, hasChange, hasDetails, isDetailsObject, splitDetails } from './details';

/**
 * Regras puras da leitura dos `details` (1211b): o que é objeto JSON, quando há detalhes a mostrar,
 * e como o antes/depois se separa do resto. Nenhuma delas interpreta o conteúdo do mapa.
 */

describe('details — leitura do JSON livre', () => {
  it('reconhece objeto JSON e recusa lista e nulo', () => {
    expect(isDetailsObject({})).toBe(true);
    expect(isDetailsObject({ total: 1 })).toBe(true);
    expect(isDetailsObject([1, 2])).toBe(false);
    expect(isDetailsObject(null)).toBe(false);
    expect(isDetailsObject('SALE')).toBe(false);
  });

  it('só considera que há detalhes quando o mapa tem alguma chave', () => {
    expect(hasDetails({ total: 1 })).toBe(true);
    expect(hasDetails({ active: false })).toBe(true);
    expect(hasDetails({})).toBe(false);
    expect(hasDetails(undefined)).toBe(false);
    expect(hasDetails(null)).toBe(false);
  });

  it('reconhece o par antes/depois por qualquer um dos lados', () => {
    expect(hasChange({ before: { total: 1 }, after: { total: 2 } })).toBe(true);
    expect(hasChange({ before: { total: 1 } })).toBe(true);
    expect(hasChange({ after: { total: 2 } })).toBe(true);
    expect(hasChange({ total: 2 })).toBe(false);
  });

  it('separa o antes/depois do resto, na ordem em que o servidor mandou', () => {
    const split = splitDetails({
      reason: 'Inventário',
      before: { quantity: 10 },
      after: { quantity: 7 },
      unitCost: 3.5,
    });

    expect(split.hasBefore).toBe(true);
    expect(split.hasAfter).toBe(true);
    expect(split.before).toEqual({ quantity: 10 });
    expect(split.after).toEqual({ quantity: 7 });
    expect(split.rest).toEqual([
      ['reason', 'Inventário'],
      ['unitCost', 3.5],
    ]);
  });

  it('distingue chave ausente de valor nulo no par', () => {
    const split = splitDetails({ before: null });

    expect(split.hasBefore).toBe(true);
    expect(split.before).toBeNull();
    expect(split.hasAfter).toBe(false);
    expect(split.after).toBeUndefined();
    expect(split.rest).toEqual([]);
  });

  it('traduz só as chaves do dicionário e mantém o resto como veio', () => {
    expect(displayKey('quantity')).toBe('Quantidade');
    expect(displayKey('method')).toBe('method');
    expect(displayKey('cashSessionId')).toBe('cashSessionId');
  });
});
