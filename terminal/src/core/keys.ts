import type { Action } from './reducer';
import type { State } from './state';

/**
 * Atalhos de teclado da operação (§11.3), em TypeScript puro: o shell entrega a tecla como o Ink a
 * mandou (`input` + flags do `useInput`) e recebe o nome lógico da tecla e o atalho que ela dispara
 * no contexto da tela (`{ screen, modal }`). Nada aqui depende de React, Ink ou rede.
 *
 * Como o Ink 7.1.1 entrega cada tecla (`ink/build/parse-keypress.js` + `ink/build/hooks/use-input.js`,
 * conferidos na versão instalada):
 * - ENTER vem como `key.return` com `input` `\r` (o `\n` cru também aparece); TAB como `key.tab` com
 *   `input` vazio; ESC, setas, DEL e BACKSPACE só aparecem nas flags, com `input` vazio;
 * - **F1–F12 não passam pelo `useInput`**: o `parse-keypress` reconhece a sequência, mas o
 *   `use-input` zera o `input` (chaves não alfanuméricas) e não expõe o nome da tecla. Por isso
 *   `resolveKey` reconhece também a **sequência crua** — exatamente as que o Ink reconhece (xterm
 *   `ESC O P`..`ESC O S`, `ESC [ 1 1 ~`..`ESC [ 2 4 ~`, e console Linux `ESC [ [ A`..`ESC [ [ E`);
 *   alimentar a sequência crua é papel do wiring do shell (1106);
 * - texto comum (dígitos, letras, espaço, colagem) não é atalho: `resolveKey` devolve `null` e o
 *   formulário fica com ele.
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

/**
 * Flags do `useInput` que interessam ao mapa; os nomes são os do `Key` do Ink, então o shell
 * repassa o `key` direto. `ctrl`/`meta` servem só para descartar combos que não são da operação.
 */
export type KeyFlags = {
  return: boolean;
  escape: boolean;
  tab: boolean;
  upArrow: boolean;
  downArrow: boolean;
  leftArrow: boolean;
  rightArrow: boolean;
  delete: boolean;
  backspace: boolean;
  ctrl: boolean;
  meta: boolean;
};

/** Tela da operação: os mesmos estados de `core/state.ts` (§11.2), sem risco de sair de sincronia. */
export type Screen = State['kind'];

/**
 * Modal bloqueante aberto sobre a tela (§11.3): enquanto um está aberto, o leitor e os atalhos não
 * atuam — só ESC, que o fecha antes de sair da tela. O autoteste do leitor (F11) entra aqui porque
 * é aberto como overlay da venda e bloqueia o resto da tela enquanto está à vista (1108); a
 * confirmação do DEL (1110) é local da tela de venda, mas o contexto é o mesmo: com ela aberta só
 * ESC resolve, e ENTER é o "sim" que o próprio overlay trata.
 */
export type ModalName =
  | 'help'
  | 'priceLookup'
  | 'discount'
  | 'customer'
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
 * Sequência crua → tecla: as mesmas que o `parse-keypress` do Ink reconhece para as teclas do mapa
 * (§11.3). Escape/ENTER/TAB aparecem aqui porque o shell também pode alimentar a sequência crua,
 * como faz com F1–F12.
 */
const RAW_KEYS: Readonly<Record<string, KeyName>> = {
  // F1–F4: xterm/gnome (`ESC O P`..`ESC O S`), vt220 (`ESC [ P`..`ESC [ S`) e rxvt (`ESC [ 1 1 ~`..)
  '\x1bOP': 'F1',
  '\x1bOQ': 'F2',
  '\x1bOR': 'F3',
  '\x1bOS': 'F4',
  '\x1b[P': 'F1',
  '\x1b[Q': 'F2',
  '\x1b[R': 'F3',
  '\x1b[S': 'F4',
  '\x1b[11~': 'F1',
  '\x1b[12~': 'F2',
  '\x1b[13~': 'F3',
  '\x1b[14~': 'F4',
  // console Linux/Cygwin: `ESC [ [ A`..`ESC [ [ E`
  '\x1b[[A': 'F1',
  '\x1b[[B': 'F2',
  '\x1b[[C': 'F3',
  '\x1b[[D': 'F4',
  '\x1b[[E': 'F5',
  // F5–F12: xterm/rxvt `ESC [ 1 5 ~`..`ESC [ 2 4 ~`
  '\x1b[15~': 'F5',
  '\x1b[17~': 'F6',
  '\x1b[18~': 'F7',
  '\x1b[19~': 'F8',
  '\x1b[20~': 'F9',
  '\x1b[21~': 'F10',
  '\x1b[23~': 'F11',
  '\x1b[24~': 'F12',
  // setas: CSI (`ESC [ A`..) e SS3 (`ESC O A`..)
  '\x1b[A': 'UP',
  '\x1bOA': 'UP',
  '\x1b[B': 'DOWN',
  '\x1bOB': 'DOWN',
  '\x1b[C': 'RIGHT',
  '\x1bOC': 'RIGHT',
  '\x1b[D': 'LEFT',
  '\x1bOD': 'LEFT',
  // DEL (`ESC [ 3 ~`), BACKSPACE (`DEL`/`BS`), TAB (`TAB`/`ESC [ Z`), ENTER e ESC crus
  '\x1b[3~': 'DEL',
  '\x7f': 'BACKSPACE',
  '\b': 'BACKSPACE',
  '\t': 'TAB',
  '\x1b[Z': 'TAB',
  '\r': 'ENTER',
  '\n': 'ENTER',
  '\x1b': 'ESC',
};

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
 * Nome lógico da tecla, ou `null` quando ela é texto/controle que não pertence ao mapa (§11.3).
 * Aceita as duas formas de entrega do Ink: as flags do `useInput` e a sequência crua.
 */
export function resolveKey(input: string, key: KeyFlags): KeyName | null {
  // Ctrl/Alt junto é do sistema ou do formulário, nunca do mapa da operação
  if (key.ctrl || key.meta) {
    return null;
  }

  if (key.return) return 'ENTER';
  if (key.escape) return 'ESC';
  if (key.tab) return 'TAB';
  if (key.upArrow) return 'UP';
  if (key.downArrow) return 'DOWN';
  if (key.leftArrow) return 'LEFT';
  if (key.rightArrow) return 'RIGHT';
  if (key.delete) return 'DEL';
  if (key.backspace) return 'BACKSPACE';

  const named = RAW_KEYS[input];
  if (named !== undefined) {
    return named;
  }

  if (input === '+') return 'PLUS';
  if (input === '-') return 'MINUS';

  return null;
}

/**
 * Sequências conhecidas da mais longa para a mais curta: na leitura de um chunk a mais longa vence
 * (`ESC [ [ A` é F1 no console Linux, não uma seta com prefixo). O ESC fica de fora: sozinho ele é
 * a tecla, e como prefixo de sequência não pode virar tecla (o `useInput` entrega o ESC).
 */
const RAW_KEY_SEQUENCES: ReadonlyArray<readonly [string, KeyName]> = Object.entries(RAW_KEYS)
  .filter(([sequence]) => sequence !== '\x1b')
  .sort(([a], [b]) => b.length - a.length);

/**
 * Teclas de um chunk cru do stdin, na ordem em que chegaram (§11.3): o wiring do shell entrega o
 * que veio do `stdin.on('data')` — um chunk pode trazer mais de uma tecla, porque o console manda a
 * sequência de F1–F12 inteira e o leitor manda a rajada com o terminador junto — e recebe de volta
 * os nomes lógicos reconhecidos.
 *
 * Só as **sequências** da tabela crua viram tecla: texto (dígitos do leitor, letras do formulário)
 * é ignorado, porque quem o trata são os `useInput` das telas. Sequência cortada entre dois chunks
 * também é ignorada (`ESC [ 2 3` sem o `~` fica pendente no console, e o ESC do começo não vira
 * tecla): completá-la exigiria guardar estado entre eventos, e um F perdido não paga um buffer que
 * pode engolir a tecla seguinte. O ESC conta só como último caractere do chunk, que é como a tecla
 * sozinha chega.
 */
export function resolveRawKeys(chunk: string): KeyName[] {
  const keys: KeyName[] = [];
  let index = 0;

  while (index < chunk.length) {
    if (chunk[index] === '\x1b' && index === chunk.length - 1) {
      keys.push('ESC');
      index += 1;
      continue;
    }

    const match = RAW_KEY_SEQUENCES.find(([sequence]) => chunk.startsWith(sequence, index));

    if (match === undefined) {
      index += 1;
      continue;
    }

    keys.push(match[1]);
    index += match[0].length;
  }

  return keys;
}

/**
 * O que a tecla faz naquele contexto, ou `null` quando ela não pertence à operação ali (§11.3).
 *
 * - modal aberto vence a tela: só ESC resolve (`closeModal`); ENTER, setas e `+`/`-` do modal são
 *   do próprio formulário/lista, que os trata localmente;
 * - `error` é falha bloqueante: só reconhecer, com ENTER (`confirm`) ou ESC (`cancel`);
 * - `paying`/`closingCash`: ESC volta para a venda com ela intacta (`cancel`);
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
