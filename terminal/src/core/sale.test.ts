import { describe, expect, test } from 'vitest';

import { touchedItem } from './sale';
import type { SaleItemView, SaleView } from './state';

/**
 * O item tocado pela resposta do servidor, usado só pela linha de confirmação do bipe (1109): o
 * que o servidor mexeu sai daqui, sem nenhuma conta da TUI (BR-12).
 */

const ARROZ: SaleItemView = {
  productId: 'p1',
  name: 'Arroz 5kg',
  quantity: 1,
  unitPrice: 24.9,
  lineTotal: 24.9,
};

const CAFE: SaleItemView = {
  productId: 'p2',
  name: 'Café 500g',
  quantity: 1,
  unitPrice: 18.5,
  lineTotal: 18.5,
};

function sale(items: SaleItemView[], id = 'sale-1'): SaleView {
  const subtotal = items.reduce((sum, item) => sum + item.lineTotal, 0);
  // fixture reproduz a conta do servidor; a TUI nunca soma nada
  return { id, items, subtotal, discountAmount: 0, total: subtotal };
}

describe('touchedItem', () => {
  test('item novo é o tocado, mesmo com a venda ainda vazia', () => {
    expect(touchedItem(null, sale([ARROZ]))).toEqual(ARROZ);
    expect(touchedItem(sale([]), sale([ARROZ]))).toEqual(ARROZ);
  });

  test('na soma do mesmo produto, o tocado é o que mudou de quantidade — não o último da lista', () => {
    const previous = sale([ARROZ, CAFE]);
    const summed = sale([{ ...ARROZ, quantity: 2, lineTotal: 49.8 }, CAFE]);

    expect(touchedItem(previous, summed)).toEqual(summed.items[0]);
  });

  test('resposta sem mudança identificável cai no último item (só para o operador ver o que voltou)', () => {
    const current = sale([ARROZ, CAFE]);

    expect(touchedItem(current, current)).toEqual(CAFE);
  });

  test('venda vazia devolve nulo: nada foi adicionado', () => {
    expect(touchedItem(null, sale([]))).toBeNull();
  });
});
