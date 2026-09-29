import { Outlet } from 'react-router';
import { SidebarNav } from './SidebarNav';

/**
 * Casca visual única da retaguarda (§10.2): cabeçalho, navegação lateral e o conteúdo da rota.
 * Cada tela entra pelo `Outlet`, então o layout não conhece feature nenhuma.
 */
export function AppLayout() {
  return (
    <div className="flex min-h-dvh flex-col">
      {/* Fora de tela até o primeiro Tab: o operador pula a navegação e cai no conteúdo. */}
      <a
        href="#conteudo"
        className="absolute left-4 top-4 z-50 -translate-y-20 rounded-md bg-surface px-4 py-2 text-sm font-medium text-ink shadow-md transition-transform duration-150 ease-out focus-visible:translate-y-0 motion-reduce:transition-none"
      >
        Ir para o conteúdo
      </a>

      <header className="flex items-center gap-3 border-b border-line bg-surface px-4 py-3 md:px-6">
        <span
          aria-hidden="true"
          className="grid size-9 shrink-0 place-items-center rounded-md bg-brand text-sm font-bold text-white"
        >
          M
        </span>
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-ink">Minimarket</p>
          <p className="text-xs text-ink-muted">Retaguarda</p>
        </div>
      </header>

      <div className="flex flex-1 flex-col md:flex-row">
        <SidebarNav />
        <main
          id="conteudo"
          // Alvo do atalho de conteúdo: focável por programa, sem anel em volta da área inteira.
          tabIndex={-1}
          className="min-w-0 flex-1 px-4 py-6 focus:outline-none md:px-8 md:py-10"
        >
          <Outlet />
        </main>
      </div>
    </div>
  );
}
