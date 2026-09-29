/**
 * Período do dia para o dashboard (1212b). O dia é o de quem opera — não o UTC do servidor —, então
 * os limites são meia-noite local e a conversão para o ISO com offset que o contrato exige fica no
 * client. O fim do dia é a meia-noite do dia seguinte (`[from, to)`, a semântica de faturamento),
 * construída com o dia do mês somado: `+24h` em milissegundos erraria a virada em fuso com horário
 * de verão, e o construtor do `Date` normaliza a virada de mês/ano em horário local.
 */

/** Limites do dia local em ISO-8601 com offset: `from` inclusivo, `to` exclusivo. */
export interface DayRange {
  from: string;
  to: string;
}

/** Limites do dia local de `now`: início da meia-noite local e a do dia seguinte. */
export function localDayRange(now: Date): DayRange {
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const end = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1);
  return { from: start.toISOString(), to: end.toISOString() };
}
