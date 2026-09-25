import type { Key } from 'ink';

/**
 * Traduz o chunk do Ink no que o leitor come, caractere a caractere: o `\r`/`\n` do ENTER vem
 * dentro do próprio `input`, o TAB chega só na flag (com `input` vazio) e teclas sem texto (ESC,
 * setas, DEL) não são leitura. Usado pelo autoteste do leitor (1104c) e pela venda (1109).
 */
export function inputChars(input: string, key: Key): string[] {
  if (key.ctrl || key.meta) {
    return [];
  }

  if (input !== '') {
    return [...input];
  }

  if (key.tab) {
    return ['\t'];
  }

  return key.return ? ['\r'] : [];
}
