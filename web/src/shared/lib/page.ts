/**
 * Página e ordenação do contrato (§9.3): os `PageResponse*` do `schema.d.ts` têm todos os campos
 * opcionais, então a UI normaliza uma única vez aqui — nenhuma feature repete `?? []` nem conta
 * com `page`/`size` preenchidos.
 */

/** Forma crua do contrato (`PageResponseX`), com os campos como o `openapi-typescript` os gera. */
export interface ApiPage<T> {
  readonly items?: readonly T[] | undefined;
  readonly page?: number | undefined;
  readonly size?: number | undefined;
  readonly totalItems?: number | undefined;
  readonly totalPages?: number | undefined;
}

/** Página pronta para a UI: campos sempre preenchidos. */
export interface Page<T> {
  items: T[];
  /** Índice 0-based, como o servidor devolve. */
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

/**
 * Normaliza a resposta paginada do contrato. Defaults documentados (o servidor sempre manda os
 * cinco campos; default só cobre resposta truncada/proxy):
 *
 * - `items` → `[]` (lista vazia é estado normal, não erro);
 * - `page` → `0`;
 * - `size` → quantidade de itens recebidos;
 * - `totalItems` → quantidade de itens recebidos;
 * - `totalPages` → `1` quando veio item, `0` quando não veio (não dá para calcular páginas sem
 *   os totais do servidor).
 *
 * O array é copiado para a tela não mutar o cache do TanStack Query por engano.
 */
export function normalizePage<T>(page: ApiPage<T> | null | undefined): Page<T> {
  const items = [...(page?.items ?? [])];
  return {
    items,
    page: page?.page ?? 0,
    size: page?.size ?? items.length,
    totalItems: page?.totalItems ?? items.length,
    totalPages: page?.totalPages ?? (items.length > 0 ? 1 : 0),
  };
}

/**
 * Ordenação do recurso: `field` é a chave da whitelist do backend para `sort` e a coluna do
 * `DataTable` só é ordenável quando a feature declara essa chave (`sortKey`).
 */
export interface Sort {
  field: string;
  direction: 'asc' | 'desc';
}

/** Valor do parâmetro `sort` (`sort=campo,asc|desc`); `null` quando não há ordenação. */
export function sortParam(sort: Sort | null | undefined): string | null {
  return sort === null || sort === undefined ? null : `${sort.field},${sort.direction}`;
}
