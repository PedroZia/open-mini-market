import { render } from 'ink-testing-library';
import { describe, expect, test, vi } from 'vitest';

import {
  ReaderSelfTestScreen,
  type BarcodeResolution,
  type BarcodeResolver,
} from './ReaderSelfTestScreen';

const BARCODE = '7891000100103';

function found(name: string, price: number, quantity: number | null = null): BarcodeResolution {
  return { found: true, product: { name, price, quantity } };
}

function failure(status: number, code: string, detail: string): BarcodeResolution {
  return { found: false, problem: { status, code, detail } };
}

/** O render do Ink não é síncrono com o `stdin.write`: espera o frame alcançar o texto. */
async function expectFrame(lastFrame: () => string | undefined, text: string): Promise<void> {
  await vi.waitFor(() => {
    expect(lastFrame()).toContain(text);
  });
}

function renderScreen(resolve?: BarcodeResolver) {
  return render(<ReaderSelfTestScreen resolve={resolve} />);
}

describe('ReaderSelfTestScreen', () => {
  test('rajada rápida em um único chunk vira leitura e aparece com o timing na tela', async () => {
    const { lastFrame, stdin } = renderScreen();

    // o Ink entrega a rajada inteira de uma vez, terminador incluso
    stdin.write(`${BARCODE}\r`);

    await expectFrame(lastFrame, `Última leitura: ${BARCODE}`);
    await expectFrame(lastFrame, 'Intervalo entre caracteres:');
  });

  test('chunks divididos continuam a mesma rajada e o TAB também fecha a leitura', async () => {
    const resolve = vi.fn(async () => found('Arroz 5kg', 24.9));
    const { lastFrame, stdin } = renderScreen(resolve);

    stdin.write('7891000');
    stdin.write('100103');
    stdin.write('\t'); // TAB chega só na flag da tecla, com `input` vazio

    await expectFrame(lastFrame, `Última leitura: ${BARCODE}`);
    expect(resolve).toHaveBeenCalledWith(BARCODE);
  });

  test('mostra o produto que o servidor devolveu', async () => {
    const resolve = vi.fn(async () => found('Arroz 5kg', 24.9));
    const { lastFrame, stdin } = renderScreen(resolve);

    stdin.write(`${BARCODE}\r`);

    await expectFrame(lastFrame, 'produto "Arroz 5kg"');
    expect(lastFrame()).toContain('R$ 24,90');
  });

  test('etiqueta de balança mostra a quantidade sugerida pelo servidor', async () => {
    const resolve = vi.fn(async () => found('Banana prata', 6.99, 0.75));
    const { lastFrame, stdin } = renderScreen(resolve);

    stdin.write('2000420001234\r');

    await expectFrame(lastFrame, 'etiqueta de balança (quantidade sugerida: 0,750)');
  });

  test('recusa do servidor mostra o code do problem+json', async () => {
    const resolve = vi.fn(async () =>
      failure(422, 'INVALID_INTERNAL_BARCODE', 'etiqueta embute valor zero'),
    );
    const { lastFrame, stdin } = renderScreen(resolve);

    stdin.write('2000420000000\r');

    await expectFrame(lastFrame, 'recusado pelo servidor: 422 INVALID_INTERNAL_BARCODE');
  });

  test('o código com separador vai bruto ao servidor e aparece bruto na tela (BR-14)', async () => {
    const resolve = vi.fn(async () =>
      failure(404, 'PRODUCT_NOT_FOUND', 'produto com código de barras 789 123 não encontrado'),
    );
    const { lastFrame, stdin } = renderScreen(resolve);

    stdin.write('789 123\r');

    await expectFrame(lastFrame, 'Última leitura: 789 123');
    expect(lastFrame()).toContain('404 PRODUCT_NOT_FOUND');
    expect(resolve).toHaveBeenCalledWith('789 123');
  });

  test('teclas de controle no meio da rajada não quebram a leitura', async () => {
    const { lastFrame, stdin } = renderScreen();

    stdin.write('789');
    stdin.write('\u001b[A'); // seta para cima: não tem texto e fica fora da rajada
    stdin.write('1000100103\r');

    await expectFrame(lastFrame, `Última leitura: ${BARCODE}`);
  });

  test('sem resolver, avisa que a interpretação depende do client de API', async () => {
    const { lastFrame, stdin } = renderScreen();

    stdin.write(`${BARCODE}\r`);

    await expectFrame(lastFrame, 'sem client de API');
  });

  test('falha inesperada do resolver não derruba a tela', async () => {
    const resolve = vi.fn(async () => {
      throw new Error('conexão caiu');
    });
    const { lastFrame, stdin } = renderScreen(resolve);

    stdin.write(`${BARCODE}\r`);

    await expectFrame(lastFrame, 'falha ao resolver: conexão caiu');
  });

  test('lista as instruções de configuração do leitor', () => {
    const { lastFrame } = renderScreen();

    const frame = lastFrame();

    expect(frame).toContain('Autoteste do leitor (F11)');
    expect(frame).toContain('Última leitura: nenhuma ainda');
    expect(frame).toContain('Sufixo: ENTER (CR) ou TAB');
    expect(frame).toContain('Prefixo/AIM ID: desligados');
    expect(frame).toContain('Simbologias: EAN-13 ligada');
    expect(frame).toContain('Layout de teclado: US');
    expect(frame).toContain('Dígito verificador (DV)');
    expect(frame).toContain('docs/leitores.md');
  });
});
