import type { PageResponseUserResponse, UserResponse } from '../../sales/api/salesApi';
import type { SalesSummaryGroupBy } from '../api/reportsApi';

/**
 * Rótulos dos relatórios (1212b): o que o servidor devolve em código (`CASH`, `2026-09-28`, UUID de
 * operador) vira texto para quem opera. É apresentação pura — nenhum número é calculado aqui.
 */

/** Rótulos pt-BR das formas do contrato; o código é que viaja e o que não está no mapa fica como veio. */
const PAYMENT_METHOD_LABELS: Record<string, string> = {
  CASH: 'Dinheiro',
  PIX: 'PIX',
  DEBIT: 'Débito',
  CREDIT: 'Crédito',
  VOUCHER: 'Vale',
};

/** Rótulo pt-BR da forma de pagamento; código desconhecido nunca ganha nome inventado. */
export function paymentMethodLabel(code: string): string {
  return PAYMENT_METHOD_LABELS[code] ?? code;
}

/** Chave do agrupamento por dia como o servidor a devolve (`to_char` em UTC). */
const DAY_KEY = /^(\d{4})-(\d{2})-(\d{2})$/;

const DAY_FORMAT = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short' });

/**
 * Chave do agrupamento por dia (`2026-09-28`) vira data pt-BR. A data é montada com os campos em
 * horário **local** de propósito: `new Date('2026-09-28')` seria meia-noite UTC e mostraria o dia
 * anterior num fuso negativo. Chave fora do formato volta como veio.
 */
export function dayLabel(key: string): string {
  const match = DAY_KEY.exec(key);
  if (match === null) {
    return key;
  }
  const [, year, month, day] = match;
  if (year === undefined || month === undefined || day === undefined) {
    return key;
  }
  return DAY_FORMAT.format(new Date(Number(year), Number(month) - 1, Number(day)));
}

/** Id curto do operador quando a tela não pode resolver o nome — nunca um nome inventado. */
export function shortId(id: string): string {
  return id.slice(0, 8);
}

/** Rótulo de um usuário do picker: nome de exibição, senão o username, senão o id curto. */
export function userLabel(user: UserResponse): string {
  if (user.displayName !== undefined && user.displayName !== '') {
    return user.displayName;
  }
  if (user.username !== undefined && user.username !== '') {
    return user.username;
  }
  return user.id === undefined ? '—' : shortId(user.id);
}

/** Mapa `id → nome` dos usuários do picker; `null` enquanto a lista não chegou. */
export function operatorNames(
  page: PageResponseUserResponse | undefined,
): ReadonlyMap<string, string> | null {
  if (page?.items === undefined) {
    return null;
  }
  const names = new Map<string, string>();
  for (const user of page.items) {
    if (user.id !== undefined) {
      names.set(user.id, userLabel(user));
    }
  }
  return names;
}

/** Nome do operador pelo mapa de `GET /users`; sem o mapa (ou sem o usuário) sobra o id curto. */
export function operatorLabel(
  operatorUserId: string | undefined,
  names: ReadonlyMap<string, string> | null,
): string {
  if (operatorUserId === undefined || operatorUserId === '') {
    return '—';
  }
  return names?.get(operatorUserId) ?? shortId(operatorUserId);
}

/** Rótulo da chave do grupo conforme a dimensão: data pt-BR, nome do operador ou forma de pagamento. */
export function groupLabel(
  groupBy: SalesSummaryGroupBy,
  key: string,
  names: ReadonlyMap<string, string> | null,
): string {
  switch (groupBy) {
    case 'day':
      return dayLabel(key);
    case 'operator':
      return operatorLabel(key, names);
    case 'paymentMethod':
      return paymentMethodLabel(key);
  }
}
