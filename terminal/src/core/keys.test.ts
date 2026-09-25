import { describe, expect, test } from 'vitest';

import {
  resolveShortcut,
  type IntentName,
  type KeyContext,
  type KeyName,
  type ModalName,
  type Screen,
  type Shortcut,
} from './keys';
import { reduce, type Action } from './reducer';
import { initialState } from './state';

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
