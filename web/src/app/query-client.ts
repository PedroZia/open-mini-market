import { QueryClient } from '@tanstack/react-query';

/** Cache único da retaguarda; `staleTime` curto para listas (§10.3 do plano). */
export const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000 } },
});
