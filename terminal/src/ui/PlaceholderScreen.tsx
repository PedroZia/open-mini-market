import { Box, Text } from 'ink';

/**
 * Tela ainda não implementada (§11): o shell renderiza uma por estado que já existe na máquina
 * (1103), para o fluxo do login terminar em algo visível em vez de tela em branco. Cada uma é
 * substituída pelo passo que a implementa — nenhuma lógica aqui.
 */
export function PlaceholderScreen({ title, step }: { title: string; step: string }) {
  return (
    <Box flexDirection="column">
      <Text bold>{title}</Text>
      <Text> </Text>
      <Text dimColor>tela do passo {step} — ainda não implementada</Text>
    </Box>
  );
}
