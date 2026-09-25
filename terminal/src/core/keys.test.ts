import { describe, expect, test } from 'vitest';

import {
  resolveKey,
  resolveRawKeys,
  resolveShortcut,
  type IntentName,
  type KeyContext,
  type KeyFlags,
  type KeyName,
  type ModalName,
  type Screen,
  type Shortcut,
} from './keys';
import { reduce, type Action } from './reducer';
import { initialState } from './state';

/** Flags do `useInput` sem tecla especial: é o que acompanha texto comum e sequência crua. */
const noFlags: KeyFlags = {
  return: false,
  escape: false,
  tab: false,
  upArrow: false,
  downArrow: false,
  leftArrow: false,
  rightArrow: false,
  delete: false,
  backspace: false,
  ctrl: false,
  meta: false,
};

/** Flags com só as teclas pedidas ligadas — o resto do `key` fica como o Ink manda. */
function flags(...pressed: Array<keyof KeyFlags>): KeyFlags {
  const key = { ...noFlags };

  for (const name of pressed) {
    key[name] = true;
  }

  return key;
}

function context(screen: Screen, modal: ModalName | null = null): KeyContext {
  return { screen, modal };
}

const sale = context('saleOpen');

/** Estreita o atalho para ação do reducer; falha o teste se vier intenção ou nada. */
function asAction(shortcut: Shortcut | null): Action {
  if (shortcut === null || shortcut.type === 'intent') {
    throw new Error(`esperava ação do reducer, veio ${JSON.stringify(shortcut)}`);
  }

  return shortcut;
}

describe('resolveKey: nome lógico da tecla', () => {
  const f1ToF4: Array<[string, KeyName]> = [
    // xterm/gnome
    ['\x1bOP', 'F1'],
    ['\x1bOQ', 'F2'],
    ['\x1bOR', 'F3'],
    ['\x1bOS', 'F4'],
    // vt220
    ['\x1b[P', 'F1'],
    ['\x1b[Q', 'F2'],
    ['\x1b[R', 'F3'],
    ['\x1b[S', 'F4'],
    // rxvt
    ['\x1b[11~', 'F1'],
    ['\x1b[12~', 'F2'],
    ['\x1b[13~', 'F3'],
    ['\x1b[14~', 'F4'],
    // console Linux/Cygwin
    ['\x1b[[A', 'F1'],
    ['\x1b[[B', 'F2'],
    ['\x1b[[C', 'F3'],
    ['\x1b[[D', 'F4'],
  ];

  test.each(f1ToF4)('F1–F4: %j vira %s', (sequence, name) => {
    expect(resolveKey(sequence, noFlags)).toBe(name);
  });

  const f5ToF12: Array<[string, KeyName]> = [
    ['\x1b[15~', 'F5'],
    ['\x1b[[E', 'F5'],
    ['\x1b[17~', 'F6'],
    ['\x1b[18~', 'F7'],
    ['\x1b[19~', 'F8'],
    ['\x1b[20~', 'F9'],
    ['\x1b[21~', 'F10'],
    ['\x1b[23~', 'F11'],
    ['\x1b[24~', 'F12'],
  ];

  test.each(f5ToF12)('F5–F12: %j vira %s', (sequence, name) => {
    expect(resolveKey(sequence, noFlags)).toBe(name);
  });

  test('a sequência de F1 não se confunde com a seta para cima', () => {
    expect(resolveKey('\x1b[[A', noFlags)).toBe('F1');
    expect(resolveKey('\x1b[A', noFlags)).toBe('UP');
  });

  const arrows: Array<[string, KeyName]> = [
    ['\x1b[A', 'UP'],
    ['\x1bOA', 'UP'],
    ['\x1b[B', 'DOWN'],
    ['\x1bOB', 'DOWN'],
    ['\x1b[C', 'RIGHT'],
    ['\x1bOC', 'RIGHT'],
    ['\x1b[D', 'LEFT'],
    ['\x1bOD', 'LEFT'],
  ];

  test.each(arrows)('seta crua: %j vira %s', (sequence, name) => {
    expect(resolveKey(sequence, noFlags)).toBe(name);
  });

  test('setas chegam também pelas flags do useInput, com input vazio', () => {
    expect(resolveKey('', flags('upArrow'))).toBe('UP');
    expect(resolveKey('', flags('downArrow'))).toBe('DOWN');
    expect(resolveKey('', flags('leftArrow'))).toBe('LEFT');
    expect(resolveKey('', flags('rightArrow'))).toBe('RIGHT');
  });

  test('ENTER vem como `\\r`, `\\n` ou na flag `return`', () => {
    expect(resolveKey('\r', noFlags)).toBe('ENTER');
    expect(resolveKey('\n', noFlags)).toBe('ENTER');
    expect(resolveKey('', flags('return'))).toBe('ENTER');
  });

  test('TAB vem na flag `tab` ou na sequência crua', () => {
    expect(resolveKey('', flags('tab'))).toBe('TAB');
    expect(resolveKey('\t', noFlags)).toBe('TAB');
    expect(resolveKey('\x1b[Z', noFlags)).toBe('TAB');
  });

  test('ESC vem na flag `escape` ou cru', () => {
    expect(resolveKey('', flags('escape'))).toBe('ESC');
    expect(resolveKey('\x1b', noFlags)).toBe('ESC');
  });

  test('DEL e BACKSPACE vêm nas flags ou crus', () => {
    expect(resolveKey('', flags('delete'))).toBe('DEL');
    expect(resolveKey('\x1b[3~', noFlags)).toBe('DEL');
    expect(resolveKey('', flags('backspace'))).toBe('BACKSPACE');
    expect(resolveKey('\x7f', noFlags)).toBe('BACKSPACE');
    expect(resolveKey('\b', noFlags)).toBe('BACKSPACE');
  });

  test('`+` e `-` viram PLUS e MINUS', () => {
    expect(resolveKey('+', noFlags)).toBe('PLUS');
    expect(resolveKey('-', noFlags)).toBe('MINUS');
  });

  const texts = ['a', 'q', '5', '0', ' ', 'R$ 5,00', 'ABC', '7891000100103'];

  test.each(texts)('texto comum %j não é atalho', (text) => {
    expect(resolveKey(text, noFlags)).toBeNull();
  });

  const unknownControls = [
    '\x1b[2~', // insert
    '\x1b[1~', // home
    '\x1b[5~', // pageup
    '\x1b[6~', // pagedown
    '\x1b[E', // clear
    '\x1b[a', // seta do rxvt sem modificador: fora do mapa
    '\x1b[999~',
    '\x1b[',
    '\x07', // bell
    '',
  ];

  test.each(unknownControls)('controle desconhecido %j não vira tecla', (input) => {
    expect(resolveKey(input, noFlags)).toBeNull();
  });

  test('combos com Ctrl/Alt não são atalhos da operação', () => {
    expect(resolveKey('c', flags('ctrl'))).toBeNull();
    expect(resolveKey('+', flags('ctrl'))).toBeNull();
    expect(resolveKey('-', flags('meta'))).toBeNull();
    expect(resolveKey('', flags('ctrl', 'escape'))).toBeNull();
  });
});

describe('resolveRawKeys: um chunk cru pode trazer mais de uma tecla', () => {
  test('a sequência de F11 vira F11 (é o canal que o `useInput` não entrega)', () => {
    expect(resolveRawKeys('\x1b[23~')).toEqual(['F11']);
  });

  test('duas teclas no mesmo chunk saem na ordem, sem virar uma só', () => {
    expect(resolveRawKeys('\x1b[23~\x1b[24~')).toEqual(['F11', 'F12']);
    expect(resolveRawKeys('\x1bOP\x1b[21~')).toEqual(['F1', 'F10']);
  });

  test('texto entre as teclas do chunk é ignorado', () => {
    expect(resolveRawKeys('\x1b[23~abc\x1bOP')).toEqual(['F11', 'F1']);
  });

  test('o bipe inteiro só entrega o terminador: os dígitos são do leitor, não do mapa', () => {
    expect(resolveRawKeys('7891000100103\r')).toEqual(['ENTER']);
    expect(resolveRawKeys('7891000100103')).toEqual([]);
  });

  test('a sequência mais longa vence: F1 do console Linux não vira seta', () => {
    expect(resolveRawKeys('\x1b[[A')).toEqual(['F1']);
    expect(resolveRawKeys('\x1b[A')).toEqual(['UP']);
  });

  test('setas, ENTER, TAB, ESC e DEL crus também resolvem', () => {
    expect(resolveRawKeys('\x1b[B\x1b[3~\t\x1b')).toEqual(['DOWN', 'DEL', 'TAB', 'ESC']);
  });

  test('sequência cortada entre dois chunks é ignorada, sem engolir a tecla seguinte', () => {
    expect(resolveRawKeys('\x1b[23')).toEqual([]);
    expect(resolveRawKeys('~')).toEqual([]);
  });

  test('controle desconhecido no meio do chunk não quebra os vizinhos', () => {
    expect(resolveRawKeys('\x1b[2~\x1b[23~')).toEqual(['F11']);
    expect(resolveRawKeys('\x1b[1~')).toEqual([]); // HOME não é do mapa, e o ESC do começo não vira tecla
    expect(resolveRawKeys('')).toEqual([]);
  });
});

describe('resolveShortcut: atalho por contexto', () => {
  const saleShortcuts: Array<[KeyName, IntentName]> = [
    ['F1', 'help'],
    ['F2', 'priceLookup'],
    ['F3', 'cancelItem'],
    ['F4', 'cancelSale'],
    ['F5', 'discount'],
    ['F6', 'customer'],
    ['F7', 'withdrawal'],
    ['F8', 'supply'],
    ['F9', 'checkout'],
    ['F10', 'closeCash'],
    ['F11', 'readerSelfTest'],
    ['F12', 'switchOperator'],
    ['UP', 'itemUp'],
    ['DOWN', 'itemDown'],
    ['PLUS', 'quantityUp'],
    ['MINUS', 'quantityDown'],
    ['DEL', 'removeItem'],
  ];

  test.each(saleShortcuts)('na venda, %s pede %s', (keyName, intent) => {
    expect(resolveShortcut(keyName, sale)).toEqual({ type: 'intent', name: intent });
  });

  const outOfContext: Array<[KeyName, KeyContext]> = [
    ['F1', context('paying')],
    ['F2', context('error')],
    ['F5', context('login')],
    ['F7', context('openingCash')],
    ['F9', context('openingCash')],
    ['F11', context('login')],
    ['F12', context('closingCash')],
    ['UP', context('paying')],
    ['DOWN', context('closingCash')],
    ['LEFT', sale],
    ['RIGHT', sale],
    ['TAB', sale],
    ['BACKSPACE', sale],
    ['ENTER', sale],
    ['PLUS', context('login')],
    ['MINUS', context('openingCash')],
    ['DEL', context('closingCash')],
  ];

  test.each(outOfContext)('%s fora do contexto não resolve nada', (keyName, ctx) => {
    expect(resolveShortcut(keyName, ctx)).toBeNull();
  });

  test('no pagamento, F9 conclui a venda (o único atalho da tela além do ESC)', () => {
    expect(resolveShortcut('F9', context('paying'))).toEqual({ type: 'intent', name: 'checkout' });
    expect(resolveShortcut('F9', sale)).toEqual({ type: 'intent', name: 'checkout' });
  });

  test('ESC só age no pagamento, no fechamento e no erro', () => {
    expect(resolveShortcut('ESC', context('paying'))).toEqual({ type: 'cancel' });
    expect(resolveShortcut('ESC', context('closingCash'))).toEqual({ type: 'cancel' });
    expect(resolveShortcut('ESC', context('error'))).toEqual({ type: 'cancel' });
    expect(resolveShortcut('ESC', sale)).toBeNull();
    expect(resolveShortcut('ESC', context('login'))).toBeNull();
    expect(resolveShortcut('ESC', context('openingCash'))).toBeNull();
  });

  test('ENTER só age no erro', () => {
    expect(resolveShortcut('ENTER', context('error'))).toEqual({ type: 'confirm' });
    expect(resolveShortcut('ENTER', context('login'))).toBeNull();
    expect(resolveShortcut('ENTER', context('openingCash'))).toBeNull();
    expect(resolveShortcut('ENTER', sale)).toBeNull();
    expect(resolveShortcut('ENTER', context('paying'))).toBeNull();
    expect(resolveShortcut('ENTER', context('closingCash'))).toBeNull();
  });

  test('login e abertura de caixa deixam o formulário inteiro para os campos', () => {
    for (const screen of ['login', 'openingCash'] as const) {
      for (const keyName of ['ENTER', 'TAB', 'UP', 'DOWN', 'PLUS', 'MINUS', 'BACKSPACE', 'F1'] as const) {
        expect(resolveShortcut(keyName, context(screen))).toBeNull();
      }
    }
  });

  const modals: ModalName[] = [
    'help',
    'priceLookup',
    'discount',
    'customer',
    'switchOperator',
    'withdrawal',
    'supply',
    'readerSelfTest',
    'removeItemConfirm',
  ];

  test.each(modals)('com o modal %s aberto, ESC fecha o modal', (modal) => {
    expect(resolveShortcut('ESC', context('saleOpen', modal))).toEqual({
      type: 'intent',
      name: 'closeModal',
    });
  });

  test('ESC fecha o modal antes de sair da tela (o modal vence o `cancel`)', () => {
    expect(resolveShortcut('ESC', context('paying', 'discount'))).toEqual({
      type: 'intent',
      name: 'closeModal',
    });
    expect(resolveShortcut('ESC', context('closingCash', 'withdrawal'))).toEqual({
      type: 'intent',
      name: 'closeModal',
    });
  });

  test('com o autoteste aberto, ESC fecha e o F11 de novo não reabre nem vaza para a venda', () => {
    expect(resolveShortcut('F11', context('saleOpen', 'readerSelfTest'))).toBeNull();
    expect(resolveShortcut('F9', context('saleOpen', 'readerSelfTest'))).toBeNull();
    expect(resolveShortcut('ESC', context('saleOpen', 'readerSelfTest'))).toEqual({
      type: 'intent',
      name: 'closeModal',
    });
  });

  test('com modal aberto, o resto dos atalhos não atua', () => {
    const blocked: KeyName[] = ['F1', 'F5', 'F9', 'F12', 'ENTER', 'UP', 'DOWN', 'PLUS', 'MINUS', 'DEL'];

    for (const keyName of blocked) {
      expect(resolveShortcut(keyName, context('saleOpen', 'customer'))).toBeNull();
    }
  });

  test('os atalhos de ESC e ENTER no erro são ações do reducer, despachadas direto', () => {
    const failing = reduce(initialState, {
      type: 'apiFailed',
      problem: { status: 503, code: null, detail: 'Serviço indisponível.' },
    });

    expect(reduce(failing, asAction(resolveShortcut('ENTER', context('error'))))).toEqual(initialState);
    expect(reduce(failing, asAction(resolveShortcut('ESC', context('error'))))).toEqual(initialState);
  });
});
