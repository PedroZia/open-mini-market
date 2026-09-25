import type { Action } from './reducer';
import type { State } from './state';

/**
 * Atalhos de teclado da operação (§11.3), em TypeScript puro: a UI entrega o **nome lógico** da tecla
 * (`KeyName`, traduzido do `KeyEvent` pelo adaptador de `src/opentui/adapters`) e recebe o atalho que
 * ela dispara no contexto da tela (`{ screen, modal }`). Nada aqui depende de React, da biblioteca de
 * UI ou de rede.
 *
 * Texto comum (dígitos, letras, espaço, colagem) não é atalho: `resolveShortcut` devolve `null` e
 * quem o trata são os campos das telas; o leitor tem o seu próprio caminho (`core/scanner`).
 *
 * A resolução por contexto (§11.3) tem uma precedência: **modal bloqueante vence a tela** — com
 * modal aberto só ESC resolve, fechando o modal antes de sair da tela; os demais atalhos (e o
 * leitor, desligado pelo shell) não atuam em modal.
 */

/** Nome lógico das teclas que a operação usa (§11.3). */
export type KeyName =
  | `F${1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12}`
  | 'ENTER'
  | 'ESC'
  | 'TAB'
  | 'UP'
  | 'DOWN'
  | 'LEFT'
  | 'RIGHT'
  | 'PLUS'
  | 'MINUS'
  | 'DEL'
  | 'BACKSPACE';

/** Tela da operação: os mesmos estados de `core/state.ts` (§11.2), sem risco de sair de sincronia. */
export type Screen = State['kind'];

/**
 * Modal bloqueante aberto sobre a tela (§11.3): enquanto um está aberto, o leitor e os atalhos não
 * atuam — só ESC, que o fecha antes de sair da tela. O autoteste do leitor (F11) entra aqui porque
 * é aberto como overlay da venda e bloqueia o resto da tela enquanto está à vista (1108); a
 * confirmação do DEL (1110) e a da troca de operador (1118) são locais, mas o contexto é o mesmo:
 * com ela aberta só ESC resolve, e ENTER é o "sim" que o próprio overlay trata.
 */
export type ModalName =
  | 'help'
  | 'priceLookup'
  | 'discount'
  | 'customer'
  | 'cancelSale'
  | 'switchOperator'
  | 'withdrawal'
  | 'supply'
  | 'readerSelfTest'
  | 'removeItemConfirm';

/** Onde a tecla foi pressionada: a tela e o modal aberto (`null` quando não há modal). */
export type KeyContext = {
  screen: Screen;
  modal: ModalName | null;
};

/**
 * Intenções nomeadas: o que a tecla pede naquele contexto (abrir modal, chamar a API, mover a
 * seleção). Quem sabe executar é a tela/o shell — o mapa não chama nada.
 */
export type IntentName =
  | 'help'
  | 'priceLookup'
  | 'cancelItem'
  | 'cancelSale'
  | 'discount'
  | 'customer'
  | 'withdrawal'
  | 'supply'
  | 'checkout'
  | 'closeCash'
  | 'readerSelfTest'
  | 'switchOperator'
  | 'itemUp'
  | 'itemDown'
  | 'quantityUp'
  | 'quantityDown'
  | 'removeItem'
  | 'closeModal';

/**
 * Atalho resolvido: `confirm`/`cancel` são **ações do reducer** (1103) e o shell despacha direto;
 * `{ type: 'intent' }` é intenção nomeada, que a tela executa.
 */
export type Shortcut =
  | Extract<Action, { type: 'confirm' | 'cancel' }>
  | { type: 'intent'; name: IntentName };

/**
 * Mapa da tela de venda (§11.3): os doze F, as setas que navegam os itens, `+`/`-` na quantidade e
 * DEL para remover. ENTER/ESC ficam de fora: na venda não há o que confirmar e ESC não abandona a
 * venda (o reducer só reage a `cancel` em `paying`/`closingCash`/`error`).
 */
const SALE_SHORTCUTS: Readonly<Partial<Record<KeyName, IntentName>>> = {
  F1: 'help',
  F2: 'priceLookup',
  F3: 'cancelItem',
  F4: 'cancelSale',
  F5: 'discount',
  F6: 'customer',
  F7: 'withdrawal',
  F8: 'supply',
  F9: 'checkout',
  F10: 'closeCash',
  F11: 'readerSelfTest',
  F12: 'switchOperator',
  UP: 'itemUp',
  DOWN: 'itemDown',
  PLUS: 'quantityUp',
  MINUS: 'quantityDown',
  DEL: 'removeItem',
};

/**
 * O que a tecla faz naquele contexto, ou `null` quando ela não pertence à operação ali (§11.3).
 *
 * - modal aberto vence a tela: só ESC resolve (`closeModal`); ENTER, setas e `+`/`-` do modal são
 *   do próprio formulário/lista, que os trata localmente;
 * - `error` é falha bloqueante: só reconhecer, com ENTER (`confirm`) ou ESC (`cancel`);
 * - `paying`: o F9 conclui a venda (a barra de status da tela mostra a tecla) e ESC volta para a
 *   venda com ela intacta (`cancel`); ENTER, setas e `+`/`-` são do formulário de pagamento, que os
 *   trata localmente;
 * - `closingCash`: ESC volta para a venda com ela intacta (`cancel`);
 * - `login`/`openingCash`: formulários — nada resolve, texto e navegação são dos campos.
 */
export function resolveShortcut(keyName: KeyName, context: KeyContext): Shortcut | null {
  if (context.modal !== null) {
    return keyName === 'ESC' ? { type: 'intent', name: 'closeModal' } : null;
  }

  switch (context.screen) {
    case 'error':
      if (keyName === 'ENTER') {
        return { type: 'confirm' };
      }
      return keyName === 'ESC' ? { type: 'cancel' } : null;
    case 'paying':
      if (keyName === 'F9') {
        return { type: 'intent', name: 'checkout' };
      }
      return keyName === 'ESC' ? { type: 'cancel' } : null;
    case 'closingCash':
      return keyName === 'ESC' ? { type: 'cancel' } : null;
    case 'saleOpen': {
      const name = SALE_SHORTCUTS[keyName];
      return name === undefined ? null : { type: 'intent', name };
    }
    case 'login':
    case 'openingCash':
      return null;
  }
}
