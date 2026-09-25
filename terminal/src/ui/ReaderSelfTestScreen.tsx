import { Box, Text, useInput } from 'ink';
import { useRef, useState } from 'react';

import { createScanner, type Scanner, type ScannerEvent } from '../core/scanner';
import type { ApiProblem } from '../core/state';
import { inputChars } from './scannerInput';

/**
 * Autoteste do leitor (F11, passo 1104c): mostra o que o leitor mandou e o que o servidor
 * respondeu, para diagnosticar o equipamento sem chamar o suporte técnico.
 *
 * A tela não interpreta nada (BR-14): exibe o código **bruto**, sem trim nem parse, e o que o
 * `resolve` injetado trouxe do servidor — o shell (1106+) liga esse resolver ao
 * `@minimarket/api-client`; aqui a tela só depende da função, então o teste roda sem rede.
 *
 * O timing é medido no wiring: o Ink pode entregar a rajada inteira de uma vez (terminador junto),
 * então cada chunk é dividido em caracteres e alimentado no `createScanner()` com
 * `performance.now()` — sem isso um bipe real não fecharia a leitura. O guia de configuração dos
 * equipamentos está em `docs/leitores.md`.
 */

/** Produto como o bipe o devolve (`GET /products/barcode/{barcode}`, passos 409/1104b3). */
export type ReaderProduct = {
  name: string;
  price: number;
  /** Quantidade sugerida pelo servidor; preenchida só quando a etiqueta de balança embute peso ou preço. */
  quantity: number | null;
};

/** Resposta do bipe: o produto resolvido pelo servidor ou o `problem+json` da recusa (§9.2). */
export type BarcodeResolution =
  | { found: true; product: ReaderProduct }
  | { found: false; problem: ApiProblem };

/**
 * Resolve o código bruto no servidor (BR-14) e devolve o que ele respondeu. Não deve rejeitar: erro
 * de API (4xx/5xx, timeout, rede) vira `found: false` com o `problem` correspondente.
 */
export type BarcodeResolver = (barcode: string) => Promise<BarcodeResolution>;

export type ReaderSelfTestScreenProps = {
  /** Sem resolver, a tela mostra só o código e o timing (autoteste sem client de API). */
  resolve?: BarcodeResolver;
};

/** Última leitura fechada pelo scanner: o código bruto e o timing da rajada que o produziu. */
type Reading = {
  barcode: string;
  /** Maior intervalo entre caracteres da rajada, em ms. */
  maxGapMs: number;
  /** Do primeiro caractere ao terminador da rajada, em ms. */
  durationMs: number;
};

/** O que a última leitura recebeu do servidor (BR-14). */
type Resolution =
  | { status: 'unavailable' }
  | { status: 'pending' }
  | { status: 'found'; product: ReaderProduct }
  | { status: 'problem'; problem: ApiProblem };

/** Instantes de caractere guardados; só a cauda da última leitura é usada, então sobra folga. */
const TIMING_WINDOW = 128;

/** Instruções do equipamento que importam para o PDV; o guia completo é `docs/leitores.md`. */
const SETUP_INSTRUCTIONS = [
  'Sufixo: ENTER (CR) ou TAB — sem ele o sistema não fecha a leitura',
  'Prefixo/AIM ID: desligados — o código vai inteiro, sem corte de dígitos',
  'Simbologias: EAN-13 ligada; desligue as que a loja não usa',
  'Layout de teclado: US — ABNT2 pode trocar caracteres do código',
  'Dígito verificador (DV): o sistema não confere o DV da etiqueta',
  'Guia completo: docs/leitores.md (inclui o que fazer quando não funciona)',
];

export function ReaderSelfTestScreen({ resolve }: ReaderSelfTestScreenProps) {
  const scannerRef = useRef<Scanner | null>(null);
  /** Instantes dos últimos caracteres alimentados: é daqui que sai o intervalo da rajada. */
  const timesRef = useRef<number[]>([]);
  /** Ordem das resoluções: resposta atrasada de uma leitura antiga não sobrescreve a nova. */
  const scanIdRef = useRef(0);

  const [reading, setReading] = useState<Reading | null>(null);
  const [resolution, setResolution] = useState<Resolution>({ status: 'unavailable' });

  /** O buffer do leitor vive fora do render: um por tela, com o timing vindo do wiring. */
  function scanner(): Scanner {
    scannerRef.current ??= createScanner();
    return scannerRef.current;
  }

  async function resolveScan(barcode: string): Promise<void> {
    if (resolve === undefined) {
      return;
    }

    const scanId = (scanIdRef.current += 1);
    setResolution({ status: 'pending' });

    try {
      const answer = await resolve(barcode);
      if (scanIdRef.current === scanId) {
        setResolution(
          answer.found
            ? { status: 'found', product: answer.product }
            : { status: 'problem', problem: answer.problem },
        );
      }
    } catch (error) {
      // contrato do resolver quebrado: a tela mostra a falha em vez de derrubar o PDV
      if (scanIdRef.current === scanId) {
        setResolution({ status: 'problem', problem: localProblem(error) });
      }
    }
  }

  useInput((input, key) => {
    for (const char of inputChars(input, key)) {
      const atMs = performance.now();
      timesRef.current.push(atMs);
      if (timesRef.current.length > TIMING_WINDOW) {
        timesRef.current.shift();
      }

      const event = scanner().feed(char, atMs);
      if (event !== null) {
        setReading(readBurst(event, timesRef.current));
        void resolveScan(event.barcode);
      }
    }
  });

  const maxGap = reading === null ? null : Math.round(reading.maxGapMs);
  const duration = reading === null ? null : Math.round(reading.durationMs);

  return (
    <Box flexDirection="column">
      <Text bold>Autoteste do leitor (F11)</Text>
      <Text> </Text>
      <Text>Última leitura: {reading === null ? 'nenhuma ainda' : reading.barcode}</Text>
      <Text>
        Intervalo entre caracteres: {maxGap === null ? '—' : `${maxGap} ms`} · rajada:{' '}
        {duration === null ? '—' : `${duration} ms`}
      </Text>
      <Text> </Text>
      <Text>Interpretação (do servidor):</Text>
      <Text> {reading === null ? 'aguardando leitura' : describeResolution(resolution)}</Text>
      <Text> </Text>
      <Text bold>Instruções de configuração</Text>
      {SETUP_INSTRUCTIONS.map((instruction) => (
        <Text key={instruction}>- {instruction}</Text>
      ))}
      <Text> </Text>
      <Text dimColor>Bipe um produto de teste: o código vai bruto ao servidor (BR-14).</Text>
    </Box>
  );
}

/**
 * Traduz o chunk do Ink no que o scanner come: helper compartilhado com a tela de venda (1109),
 * em `scannerInput.ts` — o `\r`/`\n` do ENTER vem dentro do próprio `input`, mas o TAB chega só na
 * flag (`input` vazio), e controles (ESC, setas, DEL) não têm texto e são ignorados.
 */

/** Monta a leitura com o timing da rajada: os `barcode.length` caracteres e o terminador que a fechou. */
function readBurst(event: ScannerEvent, times: readonly number[]): Reading {
  const window = times.slice(-(event.barcode.length + 1));
  const [first = 0] = window;
  let previous = first;
  let maxGapMs = 0;

  for (const atMs of window) {
    maxGapMs = Math.max(maxGapMs, atMs - previous);
    previous = atMs;
  }

  return { barcode: event.barcode, maxGapMs, durationMs: previous - first };
}

/** Linha da interpretação: o que o servidor respondeu para o último bipe (BR-14). */
function describeResolution(resolution: Resolution): string {
  switch (resolution.status) {
    case 'unavailable':
      return 'sem client de API: só o timing desta tela está ativo';
    case 'pending':
      return 'consultando o servidor...';
    case 'found': {
      const { name, price, quantity } = resolution.product;
      const found = `produto "${name}" — ${formatPrice(price)}`;
      return quantity === null
        ? found
        : `${found} — etiqueta de balança (quantidade sugerida: ${formatQuantity(quantity)})`;
    }
    case 'problem': {
      const { status, code, detail } = resolution.problem;
      return status === 0
        ? `falha ao resolver: ${detail}`
        : `recusado pelo servidor: ${status} ${code ?? 'sem código'} — ${detail}`;
    }
  }
}

/** Falha local do resolver (contrato quebrado), sem status HTTP: o `code` é nulo e o status 0. */
function localProblem(error: unknown): ApiProblem {
  return {
    status: 0,
    code: null,
    detail: error instanceof Error ? error.message : String(error),
  };
}

/** Valores do servidor só ganham formatação de exibição; nenhuma conta é feita aqui (BR-12). */
function formatPrice(price: number): string {
  return `R$ ${price.toFixed(2).replace('.', ',')}`;
}

function formatQuantity(quantity: number): string {
  return quantity.toFixed(3).replace('.', ',');
}
