import { describe, expect, it } from 'vitest';
import { formatMoney } from './money';

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
