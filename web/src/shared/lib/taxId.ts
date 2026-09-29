/**
 * CPF na tela (1207): **conveniência** do operador, espelhando o `TaxIdValidator` do servidor
 * (`customers/application`) — normaliza para só dígitos, exige onze, confere os dois verificadores e
 * rejeita a sequência de dígitos iguais. Quem decide de verdade é o servidor, que revalida a cada
 * escrita; aqui é só para o erro aparecer antes do POST, como a validação de preço do produto.
 */

/** Só os dígitos do que foi digitado; sem nenhum dígito (ou vazio) é `null` = "sem CPF". */
export function normalizeTaxId(input: string | null | undefined): string | null {
  const digits = (input ?? '').replace(/\D/g, '');
  return digits === '' ? null : digits;
}

/** Dígito na posição do CPF já normalizado (a validação garante que só há dígitos). */
function digitAt(digits: string, index: number): number {
  return digits.charCodeAt(index) - 48;
}

/**
 * Verificador dos primeiros `length` dígitos: pesos de `length + 1` até 2, resto da divisão por 11
 * e zero quando o resto é menor que 2 — a mesma conta do `TaxIdValidator`.
 */
function checkDigit(digits: string, length: number): number {
  let sum = 0;
  for (let i = 0; i < length; i++) {
    sum += digitAt(digits, i) * (length + 1 - i);
  }
  const remainder = sum % 11;
  return remainder < 2 ? 0 : 11 - remainder;
}

/**
 * `true` para CPF válido: onze dígitos, os dois verificadores e nada de todos os dígitos iguais (que
 * passa na conta, mas não é CPF). Máscara é aceita porque normaliza antes; nulo, vazio ou qualquer
 * coisa que não vire onze dígitos é `false`.
 */
export function isValidTaxId(input: string | null | undefined): boolean {
  const digits = normalizeTaxId(input);
  if (digits === null || digits.length !== 11) {
    return false;
  }
  if (/^(\d)\1{10}$/.test(digits)) {
    return false;
  }
  return (
    checkDigit(digits, 9) === digitAt(digits, 9) && checkDigit(digits, 10) === digitAt(digits, 10)
  );
}
