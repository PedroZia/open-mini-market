import { render } from 'ink-testing-library';
import { describe, expect, test } from 'vitest';

import type { SaleItemView, SaleOpenState } from '../core/state';
import { SaleScreen } from './SaleScreen';

/**
 * Tela de venda (1108, §11.3) sem shell e sem API: o que se testa é o desenho — cabeçalho, lista
 * com o último destacado, janela dos últimos itens e painel de totais com os valores que o servidor
 * mandou (nenhuma conta nasce aqui, BR-12).
 *
 * O aceite do passo é o layout: com 0, 1 e 20 itens nenhuma linha passa de 80 colunas e o frame
 * cabe nas 24 linhas do terminal. O harness renderiza com 100 colunas, então quem garante o 80×24 é
 * a asserção, não o terminal do teste.
 */

/** Data fixa: a hora entra por prop justamente para o teste não depender do relógio. */
const NOW = new Date(2026, 8, 24, 14, 32, 5);

const OPERATOR = { id: 'u1', name: 'Ana Souza' };
const REGISTER = { id: 'r1', name: 'Caixa 01' };

/** Produtos realistas da loja, na ordem em que o servidor devolveu os itens. */
const PRODUCTS = [
  'Arroz 5kg',
  'Feijão 1kg',
  'Açúcar 1kg',
  'Café 500g',
  'Óleo 900ml',
  'Leite 1L',
  'Pão de forma',
  'Ovos 12un',
  'Macarrão 500g',
  'Molho de tomate',
  'Queijo mussarela',
  'Presunto fatiado',
  'Banana prata',
  'Maçã gala',
  'Tomate',
  'Cebola',
  'Batata',
  'Sabão em pó',
  'Detergente',
  'Papel higiênico',
];

/** Estado da venda como o server devolveria: 0 item é a venda ainda não criada (`sale: null`). */
function saleWith(count: number): SaleOpenState {
  const items: SaleItemView[] = PRODUCTS.slice(0, count).map((name, index) => ({
    productId: `p${index}`,
    name,
    quantity: 2,
    unitPrice: index + 3,
    lineTotal: (index + 3) * 2,
  }));
  // o fixture repete a conta que o servidor faria; a tela só exibe (BR-12)
  const total = items.reduce((sum, item) => sum + item.lineTotal, 0);

  return {
    kind: 'saleOpen',
    operator: OPERATOR,
    register: REGISTER,
    sessionId: 's1',
    sale: count === 0 ? null : { id: 'v1', items, subtotal: total, discountAmount: 0, total },
    pendingScan: null,
  };
}

/** Linhas do frame: o harness do Ink entrega texto puro, então a largura da linha é a visível. */
function frameLines(frame: string): string[] {
  return frame.split('\n');
}

/** Aceite do 1108: o frame cabe nas 24 linhas e nenhuma linha passa de 80 colunas. */
function expectLayout(frame: string): void {
  const lines = frameLines(frame);

  expect(lines.length).toBeLessThanOrEqual(24);

  for (const line of lines) {
    expect(line.length).toBeLessThanOrEqual(80);
  }
}

/** Linhas destacadas da lista: as que começam com o marcador do último item. */
function highlighted(frame: string): string[] {
  return frameLines(frame).filter((line) => line.startsWith('› '));
}

describe('SaleScreen', () => {
  test('sem venda ainda: lista vazia, zeros de exibição e o convite ao primeiro bipe', () => {
    const { lastFrame } = render(<SaleScreen state={saleWith(0)} now={NOW} />);
    const frame = lastFrame() ?? '';

    expect(frame).toContain('PDV minimercado · Caixa 01');
    expect(frame).toContain('Operador: Ana Souza · 14:32:05');
    expect(frame).toContain('bipar o primeiro item para iniciar a venda');
    expect(frame).toContain('Subtotal: R$ 0,00');
    expect(frame).toContain('Desconto: R$ 0,00');
    expect(frame).toContain('TOTAL: R$ 0,00');
    expectLayout(frame);
  });

  test('um item: aparece com o último (e único) destacado, com o valor do servidor', () => {
    const { lastFrame } = render(<SaleScreen state={saleWith(1)} now={NOW} />);
    const frame = lastFrame() ?? '';

    expect(frame).toContain('› 2 x Arroz 5kg — R$ 6,00');
    expect(frame).toContain('TOTAL: R$ 6,00');
    expect(highlighted(frame)).toEqual(['› 2 x Arroz 5kg — R$ 6,00']);
    expectLayout(frame);
  });

  test('20 itens: a janela mostra os últimos, avisa quantos ficaram acima e destaca o último', () => {
    const { lastFrame } = render(<SaleScreen state={saleWith(20)} now={NOW} />);
    const frame = lastFrame() ?? '';

    expect(frame).toContain('… 10 itens acima');
    expect(frame).toContain('Papel higiênico');
    expect(frame).not.toContain('Arroz 5kg'); // o primeiro item ficou acima da janela
    expect(frame).toContain('TOTAL: R$ 500,00');

    const marked = highlighted(frame);
    expect(marked).toHaveLength(1);
    expect(marked[0]).toContain('Papel higiênico');
    expectLayout(frame);
  });

  test('a barra de status mostra os atalhos da operação (§11.3)', () => {
    const { lastFrame } = render(<SaleScreen state={saleWith(0)} now={NOW} />);
    const frame = lastFrame() ?? '';

    expect(frame).toContain('F1 Ajuda');
    expect(frame).toContain('F9 Pagamento');
    expect(frame).toContain('F11 Autoteste do leitor');
    expect(frame).toContain('F12 Trocar operador');
    expect(frame).toContain('↑↓ itens');
    expect(frame).toContain('DEL remove');
  });
});
