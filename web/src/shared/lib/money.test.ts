import { describe, expect, it } from 'vitest';
import { formatMoney, parseDecimalInput } from './money';

/** O `Intl` separa símbolo e valor com espaço fixo (U+00A0); normaliza para comparar o texto. */
function text(value: number): string {
  return formatMoney(value).replace(/\u00a0/g, ' ');
}

describe('formatMoney', () => {
  it('formata em pt-BR, com símbolo, separador de milhar e duas casas', () => {
    expect(text(1234.5)).toBe('R$ 1.234,50');
    expect(text(1234567.89)).toBe('R$ 1.234.567,89');
  });

  it('formata zero e centavos', () => {
    expect(text(0)).toBe('R$ 0,00');
    expect(text(0.05)).toBe('R$ 0,05');
  });
});

describe('parseDecimalInput', () => {
  it('aceita vírgula e ponto sem recalcular o valor digitado', () => {
    expect(parseDecimalInput('12,50')).toBe(12.5);
    expect(parseDecimalInput('12.50')).toBe(12.5);
    expect(parseDecimalInput(' 7 ')).toBe(7);
    expect(parseDecimalInput('0')).toBe(0);
  });

  it('recusa o que não é decimal na escala do contrato', () => {
    expect(parseDecimalInput('')).toBeNull();
    expect(parseDecimalInput('abc')).toBeNull();
    expect(parseDecimalInput('1,234')).toBeNull();
    expect(parseDecimalInput('-1')).toBeNull();
    expect(parseDecimalInput('1.234,56')).toBeNull();
  });

  it('a quantidade aceita três casas, como numeric(14,3)', () => {
    expect(parseDecimalInput('1,234', 3)).toBe(1.234);
    expect(parseDecimalInput('1,2345', 3)).toBeNull();
  });
});
