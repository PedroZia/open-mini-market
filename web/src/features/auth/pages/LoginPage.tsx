import { ApiError, ApiNetworkError, ApiTimeoutError } from '@minimarket/api-client';
import { useState, type FormEvent } from 'react';
import { Navigate, useLocation } from 'react-router';
import { useAuth } from '../AuthContext';

/**
 * Mensagem do login pelo `code` do `problem+json` (§9.2): credencial inválida, conta bloqueada e
 * rate limit têm texto próprio; o resto cai no `detail` do servidor. O tratamento global de erro é
 * o 1203 — aqui é só o que a tela de login precisa.
 */
function loginErrorMessage(failure: unknown): string {
  if (failure instanceof ApiError) {
    switch (failure.code) {
      case 'INVALID_CREDENTIALS':
        return 'Usuário ou senha inválidos.';
      case 'ACCOUNT_LOCKED':
        return 'Conta bloqueada por tentativas de login. Tente de novo mais tarde.';
      case 'RATE_LIMITED':
        return 'Muitas tentativas de login. Aguarde um instante e tente de novo.';
      case 'VALIDATION_ERROR':
        return 'Informe usuário e senha.';
      default:
        return failure.detail;
    }
  }
  if (failure instanceof ApiTimeoutError) {
    return 'O servidor não respondeu a tempo. Tente de novo.';
  }
  if (failure instanceof ApiNetworkError) {
    return 'Não foi possível falar com o servidor. Verifique a conexão.';
  }
  return 'Não foi possível entrar. Tente de novo.';
}

/** Destino guardado pela guarda de rota (`RequireAuth`); `/login` não é destino válido. */
function returnToOf(state: unknown): string {
  const from = (state as { from?: unknown } | null | undefined)?.from;
  const pathname = (from as { pathname?: unknown } | null | undefined)?.pathname;
  return typeof pathname === 'string' && pathname !== '/login' ? pathname : '/';
}

/**
 * Tela de login (1202): única rota pública, fora do layout. O 401 daqui é credencial inválida
 * (requisição anônima, sem token) — mostra a mensagem e não desloga ninguém. Com a sessão criada,
 * o componente devolve quem chegou de uma rota protegida ao destino original.
 */
export function LoginPage() {
  const { status, login } = useAuth();
  const location = useLocation();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [failure, setFailure] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const returnTo = returnToOf(location.state);

  if (status === 'authenticated') {
    return <Navigate to={returnTo} replace />;
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setFailure(null);

    try {
      await login(username, password);
      // No sucesso a sessão sobe e a primeira condição do componente redireciona.
    } catch (error) {
      setFailure(loginErrorMessage(error));
      setSubmitting(false);
    }
  }

  return (
    <main className="grid min-h-dvh place-items-center bg-canvas px-4 py-10">
      <section
        aria-labelledby="titulo-login"
        className="w-full max-w-sm rounded-lg border border-line bg-surface p-6 shadow-sm"
      >
        <h1 id="titulo-login" className="text-2xl font-semibold text-ink">
          Entrar
        </h1>
        <p className="mt-1 text-sm text-ink-muted">Acesse a retaguarda com o usuário do PDV.</p>

        <form className="mt-6 flex flex-col gap-4" onSubmit={handleSubmit}>
          <div className="flex flex-col gap-1">
            <label htmlFor="login-usuario" className="text-sm font-medium text-ink">
              Usuário
            </label>
            <input
              id="login-usuario"
              name="username"
              type="text"
              autoComplete="username"
              autoFocus
              required
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              className="min-h-11 rounded-md border border-line bg-surface px-3 text-ink"
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="login-senha" className="text-sm font-medium text-ink">
              Senha
            </label>
            <input
              id="login-senha"
              name="password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              className="min-h-11 rounded-md border border-line bg-surface px-3 text-ink"
            />
          </div>

          {failure !== null ? (
            <p role="alert" className="text-sm font-medium text-danger">
              {failure}
            </p>
          ) : null}

          <button
            type="submit"
            disabled={submitting}
            aria-busy={submitting}
            className="min-h-11 rounded-md bg-brand px-4 text-sm font-semibold text-white transition-colors hover:bg-brand/90 disabled:cursor-not-allowed disabled:opacity-60 motion-reduce:transition-none"
          >
            Entrar
          </button>
        </form>
      </section>
    </main>
  );
}
