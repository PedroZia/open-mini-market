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
