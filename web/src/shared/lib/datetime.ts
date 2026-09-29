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
