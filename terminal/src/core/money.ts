/**
 * Formatação do campo de valor da TUI (BR-12): o servidor é quem calcula dinheiro; aqui só se
 * transformam os dígitos que o operador digitou em centavos e se exibe o valor com a máscara em
 * reais. Nenhuma soma, desconto ou troco nasce nesta camada.
 */

/** Centavos dos dígitos digitados no campo com máscara: `"1250"` → `1250`; vazio → `0`. */
export function digitsToCents(digits: string): number {
  return digits === '' ? 0 : Number.parseInt(digits, 10);
}

/** Máscara do campo em pt-BR: `1250` → `R$ 12,50`. */
export function formatBRL(cents: number): string {
  return `R$ ${(cents / 100).toFixed(2).replace('.', ',')}`;
}

/** Valor para o corpo da API: centavos → reais (`1250` → `12.5`). */
export function centsToAmount(cents: number): number {
  return cents / 100;
}
