import { describe, expect, test } from 'vitest';

import { createScanner, type Scanner, type ScannerEvent } from './scanner';

const ENTER = '\r';
const TAB = '\t';
const BARCODE = '7891000100103';

type BurstOptions = {
  terminator?: string;
  startMs?: number;
  stepMs?: number;
};

/** Alimenta `text` caractere a caractere, com `stepMs` entre eles, e encerra no terminador. */
function scan(
  scanner: Scanner,
  text: string,
  { terminator = ENTER, startMs = 0, stepMs = 10 }: BurstOptions = {},
): ScannerEvent | null {
  let atMs = startMs;

  for (const char of text) {
    scanner.feed(char, atMs);
    atMs += stepMs;
  }

  return scanner.feed(terminator, atMs);
}

describe('leitor de código de barras', () => {
  test('rajada rápida vira um bipe com o código bruto, sem trim (BR-14)', () => {
    expect(scan(createScanner(), ` ${BARCODE} `)).toEqual({
      type: 'barcodeScanned',
      barcode: ` ${BARCODE} `,
      quantity: 1,
    });
  });

  test('digitação humana lenta não vira bipe', () => {
    expect(scan(createScanner(), BARCODE, { stepMs: 100 })).toBeNull();
  });

  test('ENTER isolado não emite nada', () => {
    expect(createScanner().feed(ENTER, 0)).toBeNull();
  });

  test('TAB também encerra a rajada', () => {
    expect(scan(createScanner(), BARCODE, { terminator: TAB })).toEqual({
      type: 'barcodeScanned',
      barcode: BARCODE,
      quantity: 1,
    });
  });

  test('um caractere sozinho não é leitura', () => {
    const scanner = createScanner();
    scanner.feed('7', 0);

    expect(scanner.feed(ENTER, 5)).toBeNull();
  });

  test('fronteira: 49 ms continua a rajada', () => {
    const scanner = createScanner();
    scanner.feed('7', 0);
    scanner.feed('8', 49);

    expect(scanner.feed(ENTER, 60)).toMatchObject({ barcode: '78', quantity: 1 });
  });

  test('fronteira: 50 ms descarta o trecho anterior e recomeça', () => {
    const scanner = createScanner();
    scanner.feed('7', 0);
    scanner.feed('8', 50);
    scanner.feed('9', 60);
    scanner.feed('0', 70);

    expect(scanner.feed(ENTER, 80)).toMatchObject({ barcode: '890', quantity: 1 });
  });

  test('3* faz o próximo bipe valer 3', () => {
    const scanner = createScanner();
    scanner.feed('3', 0);
    scanner.feed('*', 200);

    expect(scan(scanner, BARCODE, { startMs: 400 })).toEqual({
      type: 'barcodeScanned',
      barcode: BARCODE,
      quantity: 3,
    });
  });

  test('o multiplicador é consumido pelo bipe seguinte', () => {
    const scanner = createScanner();
    scanner.feed('3', 0);
    scanner.feed('*', 200);
    scan(scanner, BARCODE, { startMs: 400 });

    expect(scan(scanner, BARCODE, { startMs: 800 })).toMatchObject({ quantity: 1 });
  });

  test('12* multiplica por 12 mesmo digitado devagar', () => {
    const scanner = createScanner();
    scanner.feed('1', 0);
    scanner.feed('2', 120);
    scanner.feed('*', 240);

    expect(scan(scanner, BARCODE, { startMs: 400 })).toMatchObject({ quantity: 12 });
  });

  test('* sozinho não multiplica e não deixa multiplicador pendente', () => {
    const alone = createScanner();
    alone.feed('*', 0);

    expect(scan(alone, BARCODE, { startMs: 200 })).toMatchObject({ quantity: 1 });

    const repeated = createScanner();
    repeated.feed('3', 0);
    repeated.feed('*', 120);
    repeated.feed('*', 300);

    expect(scan(repeated, BARCODE, { startMs: 500 })).toMatchObject({ quantity: 1 });
  });

  test('0* não multiplica: o próximo bipe volta a valer 1', () => {
    const scanner = createScanner();
    scanner.feed('0', 0);
    scanner.feed('*', 120);

    expect(scan(scanner, BARCODE, { startMs: 300 })).toMatchObject({ quantity: 1 });
  });

  test('* no meio da rajada faz parte do código bruto (BR-14)', () => {
    expect(scan(createScanner(), '789*10')).toEqual({
      type: 'barcodeScanned',
      barcode: '789*10',
      quantity: 1,
    });
  });

  test('tecla solta antes do bipe é descartada', () => {
    const scanner = createScanner();
    scanner.feed('5', 0);

    expect(scan(scanner, BARCODE, { startMs: 1000 })).toMatchObject({ barcode: BARCODE });
  });

  test('teclas de controle no meio da rajada são ignoradas', () => {
    const scanner = createScanner();
    scanner.feed('7', 0);
    scanner.feed('8', 10);
    scanner.feed('\u001b', 20);
    scanner.feed('9', 30);
    scanner.feed('1', 40);

    expect(scanner.feed(ENTER, 50)).toMatchObject({ barcode: '7891' });
  });

  test('leitura desabilitada ignora a entrada e não vaza buffer velho', () => {
    const scanner = createScanner();
    scanner.feed('7', 0);
    scanner.feed('8', 10);

    scanner.setEnabled(false);

    expect(scanner.feed('9', 20)).toBeNull();
    expect(scanner.feed(ENTER, 30)).toBeNull();

    scanner.setEnabled(true);

    expect(scanner.feed(ENTER, 100)).toBeNull();
    expect(scan(scanner, BARCODE, { startMs: 200 })).toMatchObject({ barcode: BARCODE });
  });

  test('desabilitar o leitor esquece o multiplicador digitado', () => {
    const scanner = createScanner();
    scanner.feed('3', 0);
    scanner.feed('*', 120);

    scanner.setEnabled(false);
    scanner.setEnabled(true);

    expect(scan(scanner, BARCODE, { startMs: 300 })).toMatchObject({ quantity: 1 });
  });
});
