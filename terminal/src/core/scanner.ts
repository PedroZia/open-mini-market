import type { Action } from './reducer';

/**
 * Buffer do leitor de código de barras (§11.3), sem React, Ink ou timer próprio: o instante de cada
 * caractere entra por parâmetro (`feed(char, atMs)`), então o teste não precisa dormir.
 *
 * Regras:
 * - bipe = rajada de caracteres com intervalo < 50 ms entre si, encerrada em `ENTER` (`\r`/`\n`) ou
 *   `TAB` (`\t`); o código sai **bruto**, sem trim nem interpretação — quem interpreta é o servidor
 *   (BR-14);
 * - intervalo >= 50 ms é digitação humana: descarta o que veio antes e recomeça, e uma rajada de um
 *   caractere só não é leitura (tecla solta não vira bipe);
 * - `ENTER`/`TAB` isolado (buffer vazio) não emite nada e não deixa multiplicador pendente;
 * - multiplicador: `n*` digitado (o `*` chegando >= 50 ms depois do último caractere) faz o próximo
 *   bipe valer `n` e é consumido por ele; `*` na velocidade da rajada faz parte do código bruto; sem
 *   dígitos válidos (`*` sozinho ou `0*`), o próximo bipe volta a valer 1;
 * - `setEnabled(false)` desliga o leitor para modais bloqueantes: descarta buffer e multiplicador e
 *   ignora a entrada; reabilitar não emite buffer velho.
 */

/** Intervalo (ms) a partir do qual a digitação deixa de ser rajada do leitor. */
const BURST_MAX_INTERVAL_MS = 50;

/** Menor rajada que pode ser bipe: um caractere sozinho é tecla, não leitura. */
const MIN_BURST_LENGTH = 2;

const TERMINATORS = new Set(['\r', '\n', '\t']);
const MULTIPLIER_KEY = '*';

/** Bipe pronto para o reducer: exatamente a ação `barcodeScanned` (§11.2). */
export type ScannerEvent = Extract<Action, { type: 'barcodeScanned' }>;

export type Scanner = {
  /**
   * Consome um caractere no instante `atMs` (ms, mesma origem para toda a sessão) e devolve o bipe
   * quando o terminador fecha a rajada.
   */
  feed(char: string, atMs: number): ScannerEvent | null;
  /** Liga/desliga o leitor; desligado, descarta buffer e multiplicador e ignora a entrada. */
  setEnabled(enabled: boolean): void;
};

export function createScanner(): Scanner {
  let enabled = true;
  /** rajada em andamento; o código sai daqui exatamente como chegou. */
  let buffer = '';
  let lastAtMs: number | null = null;
  /** dígitos digitados para `n*`, independentes do timing da rajada. */
  let typedDigits = '';
  /** multiplicador já fechado por `n*`, valendo para o próximo bipe. */
  let multiplier: number | null = null;

  function clearBurst(): void {
    buffer = '';
    lastAtMs = null;
    typedDigits = '';
  }

  /** O terminador fecha a rajada: emite o bipe (se houver leitura) e consome o multiplicador. */
  function endBurst(): ScannerEvent | null {
    const barcode = buffer;
    const quantity = multiplier ?? 1;

    clearBurst();
    multiplier = null;

    return barcode.length >= MIN_BURST_LENGTH
      ? { type: 'barcodeScanned', barcode, quantity }
      : null;
  }

  function feedMultiplierKey(char: string, atMs: number): null {
    const inBurst = buffer !== '' && lastAtMs !== null && atMs - lastAtMs < BURST_MAX_INTERVAL_MS;

    if (inBurst) {
      // `*` na velocidade da rajada é conteúdo do código, não comando do operador (BR-14)
      buffer += char;
      lastAtMs = atMs;
      typedDigits = '';
      return null;
    }

    const typed = typedDigits === '' ? 0 : Number(typedDigits);

    clearBurst();
    multiplier = typed > 0 ? typed : null;
    return null;
  }

  return {
    feed(char, atMs) {
      if (!enabled) {
        return null;
      }

      if (TERMINATORS.has(char)) {
        return endBurst();
      }

      if (char === MULTIPLIER_KEY) {
        return feedMultiplierKey(char, atMs);
      }

      if (!isPrintable(char)) {
        return null;
      }

      // digitação humana: o que veio antes não é rajada
      if (lastAtMs !== null && atMs - lastAtMs >= BURST_MAX_INTERVAL_MS) {
        buffer = '';
      }

      buffer += char;
      lastAtMs = atMs;
      typedDigits = char >= '0' && char <= '9' ? typedDigits + char : '';

      return null;
    },

    setEnabled(next) {
      enabled = next;
      clearBurst();
      multiplier = null;
    },
  };
}

/** Caractere com representação na tela; teclas de controle e sequências de escape são ignoradas. */
function isPrintable(char: string): boolean {
  return char.length === 1 && char >= ' ' && char !== '\u007f';
}

/**
 * O chunk do teclado é um bipe do leitor? (1117)
 *
 * Nos campos de formulário (o desconto do F5, por exemplo) a rajada não pode virar texto nem
 * submeter: o leitor manda o código e o terminador **no mesmo chunk** — o mesmo pressuposto do
 * wiring da venda (1104a: "o Ink pode entregar a rajada inteira de uma vez, terminador incluso") —,
 * então é o chunk inteiro que o detector reconhece: `MIN_BURST_LENGTH` caracteres imprimíveis ou
 * mais fechados em `ENTER`/`TAB`. Terminador sozinho é tecla humana (o ENTER que aplica o desconto,
 * o TAB que troca de campo) e texto sem terminador (a digitação) entra no campo normalmente.
 */
export function isScanBurst(chars: readonly string[]): boolean {
  let printable = 0;
  let terminated = false;

  for (const char of chars) {
    if (TERMINATORS.has(char)) {
      terminated = true;
      continue;
    }

    if (!isPrintable(char)) {
      // controle no meio não é rajada de leitor: quem decide é o campo, como antes
      return false;
    }

    printable += 1;
  }

  return terminated && printable >= MIN_BURST_LENGTH;
}
