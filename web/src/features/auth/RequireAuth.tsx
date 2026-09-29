import { Navigate, Outlet, useLocation } from 'react-router';
import { useAuth } from './AuthContext';

/**
 * Guarda de rota (1202): sem sessão, manda ao login guardando o destino para voltar depois; com
 * sessão, deixa a rota protegida renderizar. Durante a inicialização segura a tela em vez de
 * piscar o login para quem só está com o token sendo validado.
 */
export function RequireAuth() {
  const { status } = useAuth();
  const location = useLocation();

  if (status === 'loading') {
    return (
      <div role="status" className="grid min-h-dvh place-items-center text-ink-muted">
        Validando a sessão…
      </div>
    );
  }

  if (status === 'anonymous') {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  return <Outlet />;
}
