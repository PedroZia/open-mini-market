import { QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router/dom';
import { queryClient } from './query-client';
import { router } from './router';

/** Providers do app (§10.2): cache do TanStack Query em volta do roteador de dados. */
export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  );
}
