import { Box, Text } from 'ink';

import { formatAmount } from '../core/money';
import type { SaleItemView, SaleOpenState } from '../core/state';

/**
 * Tela de venda (passo 1108, §11.3): a tela principal do operador — cabeçalho com caixa, operador e
 * hora, lista dos últimos itens com o último destacado, painel de totais e barra de status com os
 * atalhos.
 *
 * A tela não calcula nada (BR-12): subtotal, desconto e total saem de `state.sale`, como o servidor
 * mandou; antes do primeiro bipe a venda é `null` e a tela mostra a lista vazia com zeros de
 * exibição. A hora entra por prop para o desenho ser determinístico no teste.
 *
 * O nome da loja não está no estado (§11.2), então o cabeçalho mostra o que o reducer tem — caixa e
 * operador; quando a sessão carregar a loja (`GET /auth/me`), ela entra aqui, sem inchar o reducer
 * por causa de um rótulo.
 *
 * O layout assume 80×24 (§11.3): a janela mostra os últimos `MAX_ITEM_ROWS` itens — o que fica acima
 * vira uma linha com a contagem — e nenhuma linha passa de 80 colunas.
 */

/** Itens visíveis: o que sobra das 24 linhas depois de cabeçalho, totais e barra de status. */
const MAX_ITEM_ROWS = 10;

/** Atalhos da operação (§11.3) em três linhas que cabem nas 80 colunas. */
const SHORTCUT_ROWS = [
  'F1 Ajuda · F2 Preço · F3 Cancelar item · F4 Cancelar venda · F5 Desconto',
  'F6 Cliente · F7 Sangria · F8 Suprimento · F9 Pagamento · F10 Fechar caixa',
  'F11 Autoteste do leitor · F12 Trocar operador · ↑↓ itens · +/- qtd · DEL remove',
];

export type SaleScreenProps = {
  /** Estado do reducer: operador, caixa e a venda como o servidor devolveu (1103). */
  state: SaleOpenState;
  /** Hora do cabeçalho: `new Date()` no app, data fixa no teste. */
  now: Date;
};

export function SaleScreen({ state, now }: SaleScreenProps) {
  const sale = state.sale;
  const items = sale?.items ?? [];
  const visible = items.slice(-MAX_ITEM_ROWS);
  const hidden = items.length - visible.length;

  return (
    <Box flexDirection="column">
      <Text bold>PDV minimercado · {state.register.name}</Text>
      <Text>
        Operador: {state.operator.name} · {formatTime(now)}
      </Text>
      <Text> </Text>
      {hidden === 0 ? null : <Text dimColor>… {hidden} itens acima</Text>}
      {items.length === 0 ? (
        <Text dimColor>bipar o primeiro item para iniciar a venda</Text>
      ) : null}
      {visible.map((item, index) => (
        <ItemRow key={index} item={item} last={index === visible.length - 1} />
      ))}
      <Text> </Text>
      <Text>Subtotal: {formatAmount(sale?.subtotal ?? 0)}</Text>
      <Text>Desconto: {formatAmount(sale?.discountAmount ?? 0)}</Text>
      <Text bold>TOTAL: {formatAmount(sale?.total ?? 0)}</Text>
      {SHORTCUT_ROWS.map((row) => (
        <Text key={row} dimColor>
          {row}
        </Text>
      ))}
    </Box>
  );
}

/** Linha de um item: o último da lista vai destacado (§11.3). */
function ItemRow({ item, last }: { item: SaleItemView; last: boolean }) {
  return (
    <Text color={last ? 'cyan' : undefined} bold={last} wrap="truncate-end">
      {last ? '›' : ' '} {formatQuantity(item.quantity)} x {item.name} — {formatAmount(item.lineTotal)}
    </Text>
  );
}

/** Hora do cabeçalho em pt-BR, sempre com dois dígitos: `14:32:05`. */
function formatTime(now: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
}

/** Quantidade em pt-BR: inteira como `2`, fracionária como `0,750` (a de kg vem do servidor). */
function formatQuantity(quantity: number): string {
  return Number.isInteger(quantity) ? String(quantity) : quantity.toFixed(3).replace('.', ',');
}
