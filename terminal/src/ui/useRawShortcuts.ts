import { useStdin } from 'ink';
import { useEffect, useRef } from 'react';

import { resolveRawKeys, resolveShortcut, type KeyContext, type Shortcut } from '../core/keys';

/**
 * Canal cru do stdin (§11.3, risco apontado no 1105): o `useInput` do Ink **não entrega F1–F12** —
 * reconhece a sequência e zera o `input` —, então as teclas da barra de status chegam por aqui.
 *
 * O hook escuta o `stdin.on('data')`, quebra o chunk nas teclas conhecidas (`resolveRawKeys`) e
 * resolve cada uma no contexto atual (`resolveShortcut`), entregando ao callback o atalho do
 * contexto. Texto (dígitos do leitor, letras do formulário) não vira atalho, e as telas que já
 * tratam ENTER/ESC no próprio `useInput` — login, abertura de caixa e erro — continuam donas dessas
 * teclas: resolver não é executar, e quem executa é o callback (o shell age só nas intenções).
 */
export function useRawShortcuts(
  onShortcut: (shortcut: Shortcut) => void,
  context: KeyContext,
): void {
  const { stdin } = useStdin();
  /** Última versão do callback e do contexto: o listener vive no stream, não no render. */
  const latest = useRef({ onShortcut, context });

  useEffect(() => {
    latest.current = { onShortcut, context };
  });

  useEffect(() => {
    // antes de o Ink ligar o modo cru (`setEncoding('utf8')`) o chunk pode chegar como Buffer
    const listener = (chunk: string | Buffer) => {
      const data = typeof chunk === 'string' ? chunk : chunk.toString('utf8');

      for (const keyName of resolveRawKeys(data)) {
        const shortcut = resolveShortcut(keyName, latest.current.context);

        if (shortcut !== null) {
          latest.current.onShortcut(shortcut);
        }
      }
    };

    stdin.on('data', listener);
    return () => {
      stdin.off('data', listener);
    };
  }, [stdin]);
}
