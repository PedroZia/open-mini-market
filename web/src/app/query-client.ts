import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { errorMessage, isForbidden, isUnauthorized } from '../shared/lib/problem';
import { showToast } from '../shared/ui/Toast';

/**
 * Tratamento global de `problem+json` (§9.2/§10.3): o erro de qualquer query/mutation que ninguém
 * marcou como tratado vira toast com mensagem clara. Quem já mostra o erro na tela — formulário
 * com `errors[]` por campo, estado "sem permissão" — marca a query/mutation com
 * `meta: { suppressErrorToast: true }` e assume a mensagem.
 *
 * O 401 fica de fora: quem cuida dele é a sessão (1202, `api/client`), que derruba o login. O 403
 * de leitura também: a tela mostra `NoPermission`; o de escrita (mutation) vira toast, porque não
 * há tela de estado para a ação negada.
 */
function notifyGlobalError(
  error: unknown,
  meta: Record<string, unknown> | undefined,
  fromQuery: boolean,
): void {
  if (isUnauthorized(error)) {
    return;
  }
  if (fromQuery && isForbidden(error)) {
    return;
  }
  if (meta?.suppressErrorToast === true) {
    return;
  }
  showToast(errorMessage(error), 'error');
}

/**
 * Cache único da retaguarda; `staleTime` curto para listas (§10.3 do plano), com os dois canais
 * globais de erro ligados. Exportado como fábrica para o teste montar um client isolado.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { staleTime: 30_000 } },
    queryCache: new QueryCache({
      onError: (error, query) => notifyGlobalError(error, query.meta, true),
    }),
    mutationCache: new MutationCache({
      onError: (error, _variables, _onMutateResult, mutation) =>
        notifyGlobalError(error, mutation.meta, false),
    }),
  });
}

export const queryClient = createQueryClient();
