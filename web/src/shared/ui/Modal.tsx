import { useEffect, useId, useRef, type ReactNode } from 'react';

/**
 * Modal da retaguarda, sem biblioteca: painel `role="dialog"` com fundo escurecido, Esc para
 * fechar, foco que entra no painel e volta para quem o abriu, e Tab circulando só entre os
 * controles do diálogo (o fundo não recebe foco). Quem controla abrir/fechar é a feature.
 */

export interface ModalProps {
  open: boolean;
  title: string;
  onClose: () => void;
  children: ReactNode;
  /** Ações do rodapé (ex.: Cancelar/Salvar); o fechar do cabeçalho já existe sempre. */
  footer?: ReactNode;
}

const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

export function Modal({ open, title, onClose, children, footer }: ModalProps) {
  const titleId = useId();
  const panelRef = useRef<HTMLDivElement | null>(null);
  const openerRef = useRef<HTMLElement | null>(null);

  // Foco: entra no painel ao abrir e volta para o gatilho ao fechar.
  useEffect(() => {
    if (!open) {
      return;
    }

    openerRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const panel = panelRef.current;
    const firstFocusable = panel?.querySelector<HTMLElement>(FOCUSABLE_SELECTOR) ?? null;
    (firstFocusable ?? panel)?.focus();

    return () => {
      openerRef.current?.focus();
    };
  }, [open]);

  // Teclado: Esc fecha; Tab não escapa do painel.
  useEffect(() => {
    if (!open) {
      return;
    }

    function handleKeyDown(event: KeyboardEvent) {
      const panel = panelRef.current;
      if (panel === null) {
        return;
      }

      if (event.key === 'Escape') {
        event.preventDefault();
        onClose();
        return;
      }
      if (event.key !== 'Tab') {
        return;
      }

      const focusables = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR));
      const first = focusables[0];
      const last = focusables.at(-1);
      if (first === undefined || last === undefined) {
        event.preventDefault();
        panel.focus();
        return;
      }

      const active = document.activeElement;
      if (event.shiftKey && (active === first || active === panel)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && active === last) {
        event.preventDefault();
        first.focus();
      }
    }

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [open, onClose]);

  if (!open) {
    return null;
  }

  return (
    <div
      // Clique no fundo (e não no painel) fecha; `onMouseDown` evita fechar ao soltar um arrasto.
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
      className="fixed inset-0 z-40 flex items-center justify-center bg-ink/40 p-4"
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className="max-h-full w-full max-w-lg overflow-y-auto rounded-lg border border-line bg-surface p-6 shadow-lg"
      >
        <div className="flex items-start justify-between gap-4">
          <h2 id={titleId} className="text-lg font-semibold text-ink">
            {title}
          </h2>
          <button
            type="button"
            aria-label="Fechar"
            onClick={onClose}
            className="grid size-9 shrink-0 place-items-center rounded-md text-lg text-ink-muted transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none"
          >
            ×
          </button>
        </div>

        <div className="mt-4 text-sm text-ink">{children}</div>

        {footer !== undefined ? (
          <div className="mt-6 flex justify-end gap-2">{footer}</div>
        ) : null}
      </div>
    </div>
  );
}
