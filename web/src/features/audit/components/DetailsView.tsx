import { displayKey, hasChange, hasDetails, isDetailsObject, splitDetails } from '../lib/details';

/**
 * Visão legível dos `details` do evento (1211b): JSON livre renderizado como listas de definição
 * aninhadas, sem depender de biblioteca nova. Quando o evento traz `before`/`after` — o antes/depois
 * que as transações de escrita registram (§7.2) — as duas versões ficam lado a lado e as demais
 * chaves continuam visíveis abaixo, porque o mapa não é só a alteração.
 *
 * Nada é interpretado: valor desconhecido é mostrado como veio, sem formatar dinheiro nem traduzir
 * código de ação/entidade; só as chaves do dicionário de `lib/details` ganham rótulo pt-BR.
 */

/** Rótulo da chave `before`/`after` quando ela falta: o valor não existia antes/depois. */
const MISSING_LABEL = 'Sem registro';

/** Folha de JSON: nulo, booleano e número ganham texto legível — nunca somem da tela. */
function DetailsLeaf({ value }: { value: unknown }) {
  if (value === undefined) {
    return <span className="text-ink-muted">—</span>;
  }
  if (value === null) {
    return <span className="text-ink-muted">nulo</span>;
  }
  if (typeof value === 'boolean') {
    return <>{value ? 'sim' : 'não'}</>;
  }
  if (typeof value === 'number') {
    return <>{String(value)}</>;
  }
  if (typeof value === 'string') {
    return <span className="break-words">{value}</span>;
  }
  // JSON não tem função nem símbolo; qualquer coisa fora do contrato sai vazia em vez de quebrar.
  return <span className="text-ink-muted">—</span>;
}

/** Lista: cada item recebe o índice entre colchetes e mantém o valor a indentação da recursão. */
function DetailsArray({ items }: { items: unknown[] }) {
  if (items.length === 0) {
    return <span className="text-ink-muted">vazio</span>;
  }

  return (
    <ol className="flex flex-col gap-2">
      {items.map((item, index) => (
        <li key={index} className="flex gap-2">
          <span className="shrink-0 text-xs text-ink-muted">[{index}]</span>
          <div className="min-w-0 flex-1">
            <DetailsValue value={item} />
          </div>
        </li>
      ))}
    </ol>
  );
}

/** Objeto: um `dt` com o rótulo da chave e um `dd` com o valor, recursivo em qualquer nível. */
function DetailsObject({ entries }: { entries: [string, unknown][] }) {
  if (entries.length === 0) {
    return <span className="text-ink-muted">vazio</span>;
  }

  return (
    <dl className="flex flex-col gap-2">
      {entries.map(([key, item]) => (
        <div key={key} className="flex flex-col gap-0.5">
          <dt className="text-xs font-medium text-ink-muted">{displayKey(key)}</dt>
          <dd className="pl-3">
            <DetailsValue value={item} />
          </dd>
        </div>
      ))}
    </dl>
  );
}

/** Despacho por tipo JSON do valor corrente; a recursão é o que torna qualquer nível legível. */
function DetailsValue({ value }: { value: unknown }) {
  if (Array.isArray(value)) {
    return <DetailsArray items={value} />;
  }
  if (isDetailsObject(value)) {
    return <DetailsObject entries={Object.entries(value)} />;
  }
  return <DetailsLeaf value={value} />;
}

/** Uma das duas colunas do par: o rótulo e o valor do lado, ou o aviso de chave ausente. */
function ChangePane({
  label,
  present,
  value,
}: {
  label: string;
  present: boolean;
  value: unknown;
}) {
  return (
    <section
      aria-label={label}
      className="flex flex-col gap-2 rounded-md border border-line bg-canvas p-3"
    >
      <h3 className="text-xs font-semibold tracking-wide text-ink-muted uppercase">{label}</h3>
      {present ? <DetailsValue value={value} /> : <p className="text-ink-muted">{MISSING_LABEL}</p>}
    </section>
  );
}

export interface DetailsViewProps {
  /** `details` do evento, como o `AuditEventResponse` o traz: mapa livre e opcional. */
  details?: Record<string, unknown> | undefined;
}

export function DetailsView({ details }: DetailsViewProps) {
  if (details === undefined || !hasDetails(details)) {
    return <p className="text-ink-muted">Sem detalhes</p>;
  }

  if (hasChange(details)) {
    const { hasBefore, hasAfter, before, after, rest } = splitDetails(details);
    return (
      <div className="flex flex-col gap-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <ChangePane label="Antes" present={hasBefore} value={before} />
          <ChangePane label="Depois" present={hasAfter} value={after} />
        </div>
        {rest.length > 0 ? (
          <section aria-label="Demais dados" className="flex flex-col gap-2">
            <h3 className="text-xs font-semibold tracking-wide text-ink-muted uppercase">
              Demais dados
            </h3>
            <DetailsObject entries={rest} />
          </section>
        ) : null}
      </div>
    );
  }

  return <DetailsObject entries={Object.entries(details)} />;
}
