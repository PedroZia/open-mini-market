import { Box, Text, useInput } from 'ink';
import type { Dispatch } from 'react';

import { resolveKey, resolveShortcut } from '../core/keys';
import type { Action } from '../core/reducer';
import type { ApiProblem } from '../core/state';

/**
 * Falha bloqueante (§11.4): mostra o `problem+json` (status, `code` e `detail`) e o operador
 * reconhece com ENTER ou ESC, voltando ao estado de origem **sem perder nada** — quem guarda a
 * origem é o reducer (1103). A resolução da tecla é a do 1105, em contexto `error`.
 */
export function ErrorScreen({
  problem,
  dispatch,
}: {
  problem: ApiProblem;
  dispatch: Dispatch<Action>;
}) {
  useInput((input, key) => {
    const name = resolveKey(input, key);
    const shortcut = name === null ? null : resolveShortcut(name, { screen: 'error', modal: null });

    if (shortcut !== null && (shortcut.type === 'confirm' || shortcut.type === 'cancel')) {
      dispatch(shortcut);
    }
  });

  return (
    <Box flexDirection="column">
      <Text bold color="red">
        Falha na operação
      </Text>
      <Text> </Text>
      <Text>
        {problem.status} — {problem.code ?? 'sem código'} — {problem.detail}
      </Text>
      <Text> </Text>
      <Text dimColor>ENTER/ESC para voltar</Text>
    </Box>
  );
}
