import type { SaleItemView, SaleView } from './state';

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
