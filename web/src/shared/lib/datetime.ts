/**
 * Data e hora na tela: o `Instant` do contrato (ISO em UTC) vira texto pt-BR no fuso de quem opera —
 * a conversão é só de apresentação, como manda a convenção do projeto. Nada de data é calculado,
 * comparado ou enviado de volta ao servidor (BR-12).
 */

const DATE_TIME = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' });

/** Formata o instante do contrato (ex.: `2026-09-28T22:21:54Z`) como `28/09/2026, 22:21`. */
export function formatDateTime(instant: string): string {
  const date = new Date(instant);
  return Number.isNaN(date.getTime()) ? '—' : DATE_TIME.format(date);
}

/**
 * Valor do `<input type="datetime-local">` (hora local de quem opera, sem fuso) vira o ISO-8601
 * **com offset** que o servidor exige nos filtros de período: `2026-09-28T08:00` → `...T11:00:00.000Z`.
 * O conversor é só de entrada — o período é do operador, não um cálculo sobre os dados (BR-12).
 * Vazio ou fora do formato devolve `undefined`, e o filtro simplesmente não vai na query.
 */
export function localDateTimeToInstant(value: string): string | undefined {
  if (value.trim() === '') {
    return undefined;
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
}
