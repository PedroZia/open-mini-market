/**
 * Quantidade na tela: exibe em pt-BR o decimal que veio do servidor, com até três casas — a escala
 * de `numeric(14,3)` (§5). A conversão é só de apresentação (BR-12): saldo e mínimo são lidos do
 * servidor e **nunca** recalculados aqui.
 */

const QUANTITY = new Intl.NumberFormat('pt-BR', {
  minimumFractionDigits: 0,
  maximumFractionDigits: 3,
});

/** Formata a quantidade do contrato (ex.: `1234.5`) como `1.234,5`. */
export function formatQuantity(value: number): string {
  return QUANTITY.format(value);
}
