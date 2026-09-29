/**
 * Forma de UUID do contrato (§5): a tela confere só o formato para não pedir ao servidor um filtro
 * que voltaria 400 — quem valida de verdade é o `QueryParams.uuidOf` do backend. Nenhuma versão é
 * exigida: o filtro aceita qualquer UUID que o log já tenha.
 */
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** `true` quando o texto é um UUID (espaços em volta não contam); vazio não é UUID. */
export function isUuid(value: string): boolean {
  return UUID_PATTERN.test(value.trim());
}
