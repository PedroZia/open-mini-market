import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';

/**
 * Toast único da retaguarda (§10.3), sem biblioteca: o provider monta uma região viva no rodapé e
 * `showToast` empurra mensagens para ela. O canal é de módulo de propósito — quem trata erro fora
 * do React (as caches do TanStack Query, em `app/query-client`) também precisa avisar o operador.
 *
 * Sem provider montado o aviso se perde; a tela continua com o próprio estado de erro, então nada
 * fica escondido.
 */

export type ToastTone = 'success' | 'error' | 'info' | 'warning';

/** Tempo que o toast fica na tela antes de sair sozinho. */
export const TOAST_TIMEOUT_MS = 6_000;

interface ToastMessage {
  id: number;
  text: string;
  tone: ToastTone;
}

type ToastListener = (toast: ToastMessage) => void;

let listener: ToastListener | null = null;
let nextId = 1;

/** Mostra um toast para o operador; pode ser chamado fora de componente React. */
export function showToast(text: string, tone: ToastTone = 'info'): void {
  listener?.({ id: nextId, text, tone });
  nextId += 1;
}

/** Atalho dos componentes para o mesmo canal de `showToast`. */
export function useToast(): { show: (text: string, tone?: ToastTone) => void } {
  return useMemo(() => ({ show: showToast }), []);
}

const TONE_CLASS: Record<ToastTone, string> = {
  success: 'border-success/40 text-success',
  error: 'border-danger/40 text-danger',
  info: 'border-line text-ink',
  warning: 'border-warning/40 text-warning',
};

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastMessage[]>([]);
  const timers = useRef(new Map<number, ReturnType<typeof setTimeout>>());

  const dismiss = useCallback((id: number) => {
    const timer = timers.current.get(id);
    if (timer !== undefined) {
      clearTimeout(timer);
      timers.current.delete(id);
    }
    setToasts((current) => current.filter((toast) => toast.id !== id));
  }, []);

  useEffect(() => {
    listener = (toast) => {
      setToasts((current) => [...current, toast]);
      timers.current.set(
        toast.id,
        setTimeout(() => dismiss(toast.id), TOAST_TIMEOUT_MS),
      );
    };

    const activeTimers = timers.current;
    return () => {
      listener = null;
      activeTimers.forEach((timer) => clearTimeout(timer));
      activeTimers.clear();
    };
  }, [dismiss]);

  return (
    <>
      {children}
      <div className="pointer-events-none fixed inset-x-0 bottom-4 z-50 flex flex-col items-center gap-2 px-4">
        {toasts.map((toast) => (
          <div
            key={toast.id}
            // Erro interrompe o leitor de tela; o resto entra no fluxo educado.
            role={toast.tone === 'error' ? 'alert' : 'status'}
            className={`pointer-events-auto flex w-full max-w-md items-start gap-3 rounded-md border bg-surface px-4 py-3 text-sm shadow-lg ${TONE_CLASS[toast.tone]}`}
          >
            <p className="min-w-0 flex-1">{toast.text}</p>
            <button
              type="button"
              aria-label="Fechar aviso"
              onClick={() => dismiss(toast.id)}
              className="shrink-0 text-ink-muted transition-colors duration-150 ease-out hover:text-ink motion-reduce:transition-none"
            >
              ×
            </button>
          </div>
        ))}
      </div>
    </>
  );
}
