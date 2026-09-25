import { render } from 'ink-testing-library';
import { describe, expect, test } from 'vitest';

import { HelpModal } from './HelpModal';

/**
 * Ajuda (F1, passo 1116): a tela é estática — quem abre e fecha é o canal cru do shell (testado no
 * `App.test.tsx`) —, então o que se testa aqui é o conteúdo: o mapa do §11.3 inteiro, cada atalho
 * com uma linha do que faz na venda.
 */

/** As mesmas linhas da tela: tecla e descrição, na ordem do §11.3. */
const HELP_LINES: ReadonlyArray<readonly [string, string]> = [
  ['F1', 'esta ajuda'],
  ['F2', 'consulta de preço e estoque, sem vender'],
  ['F3', 'cancela o item selecionado'],
  ['F4', 'cancela a venda em andamento'],
  ['F5', 'desconto na venda (valor, percentual e motivo)'],
  ['F6', 'cliente na venda (busca por nome ou CPF)'],
  ['F7', 'sangria: retira dinheiro da gaveta'],
  ['F8', 'suprimento: coloca dinheiro na gaveta'],
  ['F9', 'pagamento e conclusão da venda'],
  ['F10', 'fechamento do caixa'],
  ['F11', 'autoteste do leitor de código de barras'],
  ['F12', 'troca o operador do caixa'],
  ['ENTER', 'confirma o bipe, a escolha na lista e a próxima venda'],
  ['ESC', 'fecha o modal e volta para a venda'],
  ['↑ ↓', 'navega nos itens da venda e nas listas'],
  ['+ -', 'altera a quantidade do item selecionado'],
  ['DEL', 'remove o item selecionado (com confirmação)'],
];

describe('HelpModal', () => {
  test('lista todos os atalhos ativos do §11.3 com o que cada um faz na venda', () => {
    const { lastFrame } = render(<HelpModal />);
    const frame = lastFrame() ?? '';

    expect(frame).toContain('Ajuda — atalhos da venda (F1)');

    for (const [keys, description] of HELP_LINES) {
      // a linha inteira: tecla e descrição, como o operador lê na tela
      expect(frame).toContain(`${keys} — ${description}`);
    }
  });

  test('o rodapé lembra que o ESC fecha e o TAB dos formulários não entra no mapa da venda', () => {
    const { lastFrame } = render(<HelpModal />);
    const frame = lastFrame() ?? '';

    expect(frame).toContain('ESC fecha e volta para a venda');
    // TAB é dos formulários, não da venda: não entra no mapa
    expect(frame).not.toContain('TAB');
  });
});
