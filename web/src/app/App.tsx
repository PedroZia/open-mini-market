import { QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router/dom';
import { ToastProvider } from '../shared/ui/Toast';
import { queryClient } from './query-client';
import { router } from './router';

/** Providers do app (§10.2): cache do TanStack Query e avisos, em volta do roteador de dados. */
export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>
    </QueryClientProvider>
  );
}
