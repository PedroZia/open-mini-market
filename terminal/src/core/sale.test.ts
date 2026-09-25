import { describe, expect, test } from 'vitest';

import { moveSelection, nextQuantity, quantityStep, selectionIndex, touchedItem } from './sale';
import type { SaleItemView, SaleView } from './state';

/**
 * Regras puras da tela de venda: o item tocado pela resposta do servidor, usado só pela linha de
 * confirmação do bipe (1109), a seleção local da lista e o passo do `+`/`-` (1110) — sem nenhuma
 * conta da TUI (BR-12).
 */

const ARROZ: SaleItemView = {
  productId: 'p1',
  name: 'Arroz 5kg',
  unit: 'UN',
  quantity: 1,
  unitPrice: 24.9,
  lineTotal: 24.9,
};

const CAFE: SaleItemView = {
  productId: 'p2',
  name: 'Café 500g',
  unit: 'UN',
  quantity: 1,
  unitPrice: 18.5,
  lineTotal: 18.5,
};

/** Banana é vendida a granel: a quantidade vem do servidor (etiqueta de balança) em kg. */
const BANANA: SaleItemView = {
  productId: 'p3',
  name: 'Banana prata',
  unit: 'KG',
  quantity: 0.75,
  unitPrice: 6.99,
  lineTotal: 5.24,
};

function sale(items: SaleItemView[], id = 'sale-1'): SaleView {
  const subtotal = items.reduce((sum, item) => sum + item.lineTotal, 0);
  // fixture reproduz a conta do servidor; a TUI nunca soma nada
  return { id, items, subtotal, discountAmount: 0, total: subtotal, customerId: null };
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

describe('selectionIndex', () => {
  test('sem seta, a seleção acompanha o último item — o destaque do 1108', () => {
    expect(selectionIndex(null, 1)).toBe(0);
    expect(selectionIndex(null, 3)).toBe(2);
  });

  test('índice que a lista encolheu cai no último (o item removido saiu debaixo da seleção)', () => {
    expect(selectionIndex(2, 2)).toBe(1);
    expect(selectionIndex(5, 3)).toBe(2);
  });

  test('índice dentro da lista é respeitado', () => {
    expect(selectionIndex(0, 3)).toBe(0);
    expect(selectionIndex(1, 3)).toBe(1);
  });

  test('lista vazia não tem seleção', () => {
    expect(selectionIndex(null, 0)).toBe(-1);
    expect(selectionIndex(2, 0)).toBe(-1);
  });
});

describe('moveSelection', () => {
  test('a primeira seta sai do "acompanha o último" e sobe um item', () => {
    expect(moveSelection(null, -1, 3)).toBe(1);
  });

  test('nas pontas a seta não muda nada: clamp, sem ciclo', () => {
    expect(moveSelection(2, 1, 3)).toBe(2);
    expect(moveSelection(0, -1, 3)).toBe(0);
    expect(moveSelection(null, 1, 3)).toBe(2);
  });

  test('desce e sobe dentro da lista', () => {
    expect(moveSelection(1, 1, 3)).toBe(2);
    expect(moveSelection(2, -1, 3)).toBe(1);
  });

  test('lista vazia continua sem seleção', () => {
    expect(moveSelection(null, -1, 0)).toBe(-1);
    expect(moveSelection(0, 1, 0)).toBe(-1);
  });
});

describe('nextQuantity', () => {
  test('em UN o passo é 1: `+` soma e `-` subtrai item inteiro', () => {
    expect(nextQuantity(1, 'UN', 1)).toBe(2);
    expect(nextQuantity(3, 'UN', -1)).toBe(2);
  });

  test('em KG o passo é 0,1, sem ruído de ponto flutuante', () => {
    expect(nextQuantity(BANANA.quantity, BANANA.unit, 1)).toBe(0.85);
    expect(nextQuantity(BANANA.quantity, BANANA.unit, -1)).toBe(0.65);
    expect(nextQuantity(1, 'KG', -1)).toBe(0.9);
  });

  test('unidade fora do contrato cai no passo de UN', () => {
    expect(quantityStep('UN')).toBe(1);
    expect(quantityStep('KG')).toBe(0.1);
    expect(quantityStep('')).toBe(1);
  });

  test('a quantidade pode chegar a zero: quem remove é o DEL, e a tela decide o aviso', () => {
    expect(nextQuantity(1, 'UN', -1)).toBe(0);
    expect(nextQuantity(0.1, 'KG', -1)).toBe(0);
  });
});
