/**
 * Leitura dos `details` do log de auditoria (1211b). O mapa é JSON livre: a tela não conhece o
 * catálogo de chaves nem recalcula nada (BR-12) — aqui só se decide o que é estrutura (objeto ou
 * lista), o que é folha e qual rótulo pt-BR usar para as chaves que o log mais repete. Chave fora
 * do dicionário sai exatamente como o servidor a escreveu.
 */

/** Chaves do antes/depois registrado pelas transações de escrita (§7.2). */
export const BEFORE_KEY = 'before';
export const AFTER_KEY = 'after';

/**
 * Rótulo pt-BR das chaves recorrentes cujo nome cru atrapalha a leitura. Ficam de fora os termos
 * ambíguos entre módulos (ex.: `method` é forma de pagamento numa venda e método HTTP num
 * `ACCESS_DENIED`) — para esses, e para qualquer chave nova, vale o nome do servidor.
 */
const KEY_LABELS: Record<string, string> = {
  [BEFORE_KEY]: 'Antes',
  [AFTER_KEY]: 'Depois',
  reason: 'Motivo',
  status: 'Situação',
  quantity: 'Quantidade',
  previousQuantity: 'Quantidade anterior',
  name: 'Nome',
  username: 'Usuário',
  active: 'Ativo',
  price: 'Preço',
  amount: 'Valor',
  total: 'Total',
  itemCount: 'Itens',
};

/** Rótulo de exibição da chave; sem tradução, o próprio nome que veio do servidor. */
export function displayKey(key: string): string {
  return KEY_LABELS[key] ?? key;
}

/** `true` quando o valor é um objeto JSON (não nulo e não lista). */
export function isDetailsObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** `true` quando há detalhes a mostrar: ausente, nulo ou mapa vazio é evento sem detalhes. */
export function hasDetails(details: Record<string, unknown> | null | undefined): boolean {
  return details !== null && details !== undefined && Object.keys(details).length > 0;
}

/** `true` quando os detalhes trazem `before` ou `after` — o par que a visão lado a lado lê. */
export function hasChange(details: Record<string, unknown>): boolean {
  return BEFORE_KEY in details || AFTER_KEY in details;
}

export interface SplitDetails {
  /** O mapa tinha a chave `before`? Ausente é diferente de `null` (campo sem valor). */
  hasBefore: boolean;
  hasAfter: boolean;
  before: unknown;
  after: unknown;
  /** Demais chaves, na ordem em que o servidor as mandou. */
  rest: [string, unknown][];
}

/** Separa o antes/depois do resto: só o par vira as duas colunas; o resto continua visível. */
export function splitDetails(details: Record<string, unknown>): SplitDetails {
  const rest: [string, unknown][] = [];
  for (const [key, value] of Object.entries(details)) {
    if (key !== BEFORE_KEY && key !== AFTER_KEY) {
      rest.push([key, value]);
    }
  }
  return {
    hasBefore: BEFORE_KEY in details,
    hasAfter: AFTER_KEY in details,
    before: details[BEFORE_KEY],
    after: details[AFTER_KEY],
    rest,
  };
}
