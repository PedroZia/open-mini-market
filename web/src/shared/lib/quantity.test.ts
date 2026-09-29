import { describe, expect, it } from 'vitest';
import { formatQuantity } from './quantity';

describe('formatQuantity', () => {
  it('formata em pt-BR com vírgula e separador de milhar', () => {
    expect(formatQuantity(1234.5)).toBe('1.234,5');
    expect(formatQuantity(10)).toBe('10');
    expect(formatQuantity(0)).toBe('0');
  });

  it('mostra até três casas, como numeric(14,3)', () => {
    expect(formatQuantity(1.5)).toBe('1,5');
    expect(formatQuantity(2.25)).toBe('2,25');
    expect(formatQuantity(0.125)).toBe('0,125');
  });
});
