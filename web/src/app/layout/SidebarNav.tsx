import { NavLink } from 'react-router';

/**
 * Itens da navegação lateral: só rotas que existem de verdade. Os módulos da retaguarda
 * (produtos, estoque, caixa...) entram aqui conforme cada passo cria a rota — sem link morto.
 */
const navigationItems = [{ to: '/', label: 'Início' }] as const;

const itemClassName =
  'relative flex min-h-11 items-center rounded-md px-3 text-sm transition-colors duration-150 ease-out focus-visible:outline-nav-ink motion-reduce:transition-none';

/** Navegação principal da retaguarda: coluna à esquerda no desktop, barra no topo no estreito. */
export function SidebarNav() {
  return (
    <nav
      aria-label="Navegação principal"
      className="bg-nav px-2 py-2 md:w-64 md:shrink-0 md:px-3 md:py-4"
    >
      <ul className="flex gap-1 md:flex-col md:gap-0.5">
        {navigationItems.map((item) => (
          <li key={item.to} className="md:w-full">
            <NavLink
              to={item.to}
              // `/` é a exceção do NavLink que casaria com qualquer rota: `end` deixa explícito
              // que "Início" só fica ativo na raiz.
              end
              className={({ isActive }) =>
                `${itemClassName} ${
                  isActive
                    ? 'bg-nav-active font-semibold text-white'
                    : 'text-nav-ink-muted hover:bg-white/10 hover:text-white'
                }`
              }
            >
              {({ isActive }) => (
                <>
                  {isActive ? (
                    <span
                      aria-hidden="true"
                      className="absolute inset-y-1.5 left-0 w-1 rounded-full bg-nav-indicator"
                    />
                  ) : null}
                  {item.label}
                </>
              )}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  );
}
