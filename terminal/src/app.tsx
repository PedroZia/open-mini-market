import { Box, Text, useApp, useInput } from 'ink';

/**
 * Tela inicial da TUI. Só exibe o "hello" da fase 11 e encerra com `q`;
 * a máquina de estados vive em `core/state.ts` + `core/reducer.ts` e é usada pelas telas daqui em diante.
 */
export function App() {
  const { exit } = useApp();

  useInput((input) => {
    if (input === 'q') {
      exit();
    }
  });

  return (
    <Box flexDirection="column">
      <Text>hello, PDV minimercado!</Text>
      <Text dimColor>pressione q para sair</Text>
    </Box>
  );
}
