import { describe, expect, test } from 'vitest';

import { centsToAmount, digitsToCents, formatBRL } from './money';

/**
 * Máscara do campo de valor (1107): dígitos viram centavos, centavos viram a exibição em reais e o
 * valor que vai à API. Só formatação — nenhum cálculo de negócio (BR-12).
 */
describe('formatação de dinheiro', () => {
  test('dígitos do campo viram centavos', () => {
    expect(digitsToCents('1250')).toBe(1250);
    expect(digitsToCents('5')).toBe(5);
    expect(digitsToCents('')).toBe(0);
  });

  test('centavos viram a máscara em reais', () => {
    expect(formatBRL(1250)).toBe('R$ 12,50');
    expect(formatBRL(0)).toBe('R$ 0,00');
    expect(formatBRL(5)).toBe('R$ 0,05');
    expect(formatBRL(123456)).toBe('R$ 1234,56');
  });

  test('centavos viram o valor em reais do corpo da API', () => {
    expect(centsToAmount(1250)).toBe(12.5);
    expect(centsToAmount(0)).toBe(0);
  });
});
