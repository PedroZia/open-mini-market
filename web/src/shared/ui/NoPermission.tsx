import { useId } from 'react';

/**
 * Estado "sem permissão" (§10.3): o `403` nunca vira tela em branco. A feature dona da query
 * mostra este componente quando o erro é 403 (`isForbidden`) — o servidor continua sendo a
 * autoridade; aqui é só o recado para o operador.
 */

export interface NoPermissionProps {
  /** Refaz a busca (útil quando a sessão pode ter mudado, ex.: troca de papel em outra aba). */
  onRetry?: () => void;
}

export function NoPermission({ onRetry }: NoPermissionProps) {
  const titleId = useId();

  return (
    <section
      role="alert"
      aria-labelledby={titleId}
      className="rounded-lg border border-line bg-surface p-6"
    >
      <h2 id={titleId} className="text-lg font-semibold text-ink">
        Sem permissão
      </h2>
      <p className="mt-1 text-sm text-ink-muted">
        Sua conta não tem permissão para acessar esta área. Fale com um administrador.
      </p>
      {onRetry !== undefined ? (
        <button
          type="button"
          onClick={onRetry}
          className="mt-4 min-h-9 rounded-md border border-line px-3 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none"
        >
          Tentar de novo
        </button>
      ) : null}
    </section>
  );
}
