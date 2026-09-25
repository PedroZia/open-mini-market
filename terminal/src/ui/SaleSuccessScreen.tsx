import { Box, Text, useInput } from 'ink';
import type { Dispatch } from 'react';

import { resolveKey } from '../core/keys';
import { formatAmount } from '../core/money';
import type { Action } from '../core/reducer';
import type { ReceiptView } from '../core/state';

/**
 * Venda concluída (1113): o resumo que o servidor devolveu no `complete` — número, total e troco —
 * no lugar da venda, esperando o ENTER que começa a próxima. A TUI não calcula nada aqui (BR-12):
 * os três valores são os do corpo da resposta, e o troco (BR-05: só o dinheiro tem) fica em
 * destaque quando existe. Quem limpa o resumo é o reducer (`receiptDismissed`), não esta tela.
 */
export function SaleSuccessScreen({
  receipt,
  dispatch,
}: {
  receipt: ReceiptView;
  dispatch: Dispatch<Action>;
}) {
  useInput((input, key) => {
    if (resolveKey(input, key) === 'ENTER') {
      dispatch({ type: 'receiptDismissed' });
    }
  });

  const hasChange = receipt.changeAmount > 0;

  return (
    <Box flexDirection="column">
      <Text bold color="green">
        Venda {receipt.number} concluída
      </Text>
      <Text> </Text>
      <Text bold>TOTAL: {formatAmount(receipt.total)}</Text>
      <Text bold={hasChange} color={hasChange ? 'green' : undefined}>
        TROCO: {formatAmount(receipt.changeAmount)}
      </Text>
      <Text> </Text>
      <Text dimColor>ENTER inicia a próxima venda</Text>
    </Box>
  );
}
