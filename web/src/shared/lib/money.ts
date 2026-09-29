/**
 * Dinheiro na tela: exibe em pt-BR/BRL o valor que veio do servidor, envia cru e **nunca
 * recalcula** total, desconto ou saldo (BR-12 do §4.4 do plano). A conversão é só de apresentação
 * — nenhum arredondamento nasce aqui.
 */

const BRL = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });

/** Formata o decimal do contrato (ex.: `1234.5`) como `R$ 1.234,50`. */
export function formatMoney(value: number): string {
  return BRL.format(value);
}

/**
 * Lê o decimal digitado pelo operador para o número cru do contrato — a conversão é de **entrada**,
 * nunca de cálculo (BR-12): `"12,50"` e `"12.50"` viram `12.5` e nada é arredondado aqui. `null`
 * quando o texto não é um decimal válido com a escala do contrato (preço 2 casas, quantidade 3).
 */
export function parseDecimalInput(input: string, fractions: 2 | 3 = 2): number | null {
  const text = input.trim().replace(',', '.');
  if (!new RegExp(`^\\d+(\\.\\d{1,${fractions}})?$`).test(text)) {
    return null;
  }
  const value = Number(text);
  return Number.isFinite(value) ? value : null;
}
