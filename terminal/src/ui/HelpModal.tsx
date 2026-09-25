import { Box, Text } from 'ink';

/**
 * Ajuda (F1, passo 1116): o mapa de teclas da operação (§11.3) à mão do operador, como overlay
 * bloqueante sobre a venda — o corpo sai de cena (o leitor fica inerte) e o ESC do canal cru do
 * shell fecha, voltando para a venda exatamente como estava.
 *
 * A tela não age sobre nada: é o mesmo mapa que `core/keys` resolve para a tela de venda, com uma
 * linha do que cada tecla faz ali. O F12 entra aqui porque é a tecla que a barra de status anuncia;
 * TAB fica de fora porque é dos formulários, não da venda.
 */

/** Uma linha do mapa: a tecla como o teclado a mostra e o que ela faz na venda. */
type HelpEntry = { keys: string; description: string };

const HELP_ENTRIES: readonly HelpEntry[] = [
  { keys: 'F1', description: 'esta ajuda' },
  { keys: 'F2', description: 'consulta de preço e estoque, sem vender' },
  { keys: 'F3', description: 'cancela o item selecionado' },
  { keys: 'F4', description: 'cancela a venda em andamento' },
  { keys: 'F5', description: 'desconto na venda (valor, percentual e motivo)' },
  { keys: 'F6', description: 'cliente na venda (busca por nome ou CPF)' },
  { keys: 'F7', description: 'sangria: retira dinheiro da gaveta' },
  { keys: 'F8', description: 'suprimento: coloca dinheiro na gaveta' },
  { keys: 'F9', description: 'pagamento e conclusão da venda' },
  { keys: 'F10', description: 'fechamento do caixa' },
  { keys: 'F11', description: 'autoteste do leitor de código de barras' },
  { keys: 'F12', description: 'troca o operador do caixa' },
  { keys: 'ENTER', description: 'confirma o bipe, a escolha na lista e a próxima venda' },
  { keys: 'ESC', description: 'fecha o modal e volta para a venda' },
  { keys: '↑ ↓', description: 'navega nos itens da venda e nas listas' },
  { keys: '+ -', description: 'altera a quantidade do item selecionado' },
  { keys: 'DEL', description: 'remove o item selecionado (com confirmação)' },
];

export function HelpModal() {
  return (
    <Box flexDirection="column">
      <Text bold>Ajuda — atalhos da venda (F1)</Text>
      <Text> </Text>
      {HELP_ENTRIES.map((entry) => (
        <Text key={entry.keys} wrap="truncate-end">
          <Text bold>{entry.keys}</Text> — {entry.description}
        </Text>
      ))}
      <Text> </Text>
      <Text dimColor>ESC fecha e volta para a venda</Text>
    </Box>
  );
}
