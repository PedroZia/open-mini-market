import type { SaleItemView, SaleView } from './state';

/**
 * Regras puras da tela de venda, testáveis sem render: o item que a resposta do servidor mexeu
 * (1109), a seleção local da lista e o passo do `+`/`-` (1110). Nada aqui calcula dinheiro ou total
 * (BR-12): os valores exibidos continuam sendo os do servidor.
 */

/**
 * Item que a resposta do servidor mexeu: o novo ou o que mudou de quantidade/valor de linha em
 * relação à venda que a tela tinha. Serve só para a linha de confirmação do bipe (1109) — os
 * valores exibidos continuam sendo os do servidor, nada é recalculado aqui (BR-12). Sem mudança
 * identificável (resposta fora do esperado), devolve o último item só para o operador ver o que
 * voltou.
 */
export function touchedItem(previous: SaleView | null, sale: SaleView): SaleItemView | null {
  const before = new Map((previous?.items ?? []).map((item) => [item.productId, item]));
  let touched: SaleItemView | null = null;

  for (const item of sale.items) {
    const old = before.get(item.productId);

    if (old === undefined || old.quantity !== item.quantity || old.lineTotal !== item.lineTotal) {
      touched = item;
    }
  }

  return touched ?? sale.items.at(-1) ?? null;
}

/**
 * Índice do item selecionado: `null` acompanha o último item — o padrão da tela, o mesmo destaque
 * do 1108 — e um índice que a lista encolheu (item removido na resposta) cai no último. Lista vazia
 * não tem seleção (`-1`).
 */
export function selectionIndex(selected: number | null, length: number): number {
  if (length === 0) {
    return -1;
  }

  return selected === null || selected >= length ? length - 1 : selected;
}

/**
 * Seleção depois de uma seta, com **clamp** e não com ciclo: nas pontas a seta não muda nada (o
 * ciclo numa lista longa surpreende mais do que ajuda). A primeira seta fixa o índice — antes dela a
 * seleção acompanha o último item, que é o que acabou de entrar na venda.
 */
export function moveSelection(selected: number | null, delta: number, length: number): number {
  if (length === 0) {
    return -1;
  }

  return Math.min(Math.max(selectionIndex(selected, length) + delta, 0), length - 1);
}

/**
 * Passo do `+`/`-` por unidade do produto: **1 em `UN` e 0,1 em `KG`** (venda a granel — banana,
 * tomate). É granularidade de **entrada**, não cálculo de negócio (BR-12): o servidor recalcula a
 * linha e os totais com a quantidade absoluta que o `PATCH` manda. Unidade fora do contrato cai no
 * passo de `UN`, o caso comum da loja.
 */
export function quantityStep(unit: string): number {
  return unit === 'KG' ? 0.1 : 1;
}

/**
 * Quantidade nova depois de `+`/`-`, já na escala 3 do contrato (`numeric(14,3)`): sem o ruído de
 * ponto flutuante de somar 0,1 (o que iria ao servidor como `0.8500000000000001`). Quem decide o que
 * fazer com quantidade ≤ 0 é a tela — aqui não há regra de venda.
 */
export function nextQuantity(quantity: number, unit: string, direction: 1 | -1): number {
  return Math.round((quantity + direction * quantityStep(unit)) * 1000) / 1000;
}
