import { ApiError } from '@minimarket/api-client';
import { QueryClientProvider, useMutation, useQuery } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';
import { ToastProvider } from '../shared/ui/Toast';
import { createQueryClient } from './query-client';

/**
 * Tratamento global de erro (§10.3): o que o operador vê quando uma query/mutation falha sem
 * tratamento próprio — e o que **não** aparece quando alguém já cuida do erro.
 */

function renderWithProviders(ui: ReactNode) {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <ToastProvider>{ui}</ToastProvider>
    </QueryClientProvider>,
  );
}

function FailingQuery({ error, meta }: { error: unknown; meta?: Record<string, unknown> }) {
  const query = useQuery({
    queryKey: ['probe'],
    queryFn: async () => {
      throw error;
    },
    retry: false,
    meta,
  });

  return <p>{query.isError ? 'consulta falhou' : 'consultando'}</p>;
}

function FailingMutation({ error, meta }: { error: unknown; meta?: Record<string, unknown> }) {
  const mutation = useMutation({
    mutationFn: async () => {
      throw error;
    },
    meta,
  });

  return (
    <>
      <button type="button" onClick={() => mutation.mutate()}>
        Disparar
      </button>
      <p>{mutation.isError ? 'escrita falhou' : 'pronto'}</p>
    </>
  );
}

describe('tratamento global de erro do Query', () => {
  it('erro de consulta vira toast com a mensagem do code, sem detalhe técnico', async () => {
    renderWithProviders(
      <FailingQuery
        error={new ApiError(500, { code: 'INTERNAL_ERROR', detail: 'java.lang.IllegalStateException' })}
      />,
    );

    const toast = await screen.findByRole('alert');
    expect(toast).toHaveTextContent('O servidor falhou ao responder. Tente de novo em instantes.');
    expect(toast).not.toHaveTextContent('IllegalStateException');
  });

  it('não duplica o 401, que é tratado pela sessão', async () => {
    renderWithProviders(<FailingQuery error={new ApiError(401, { code: 'SESSION_EXPIRED' })} />);

    expect(await screen.findByText('consulta falhou')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('deixa o 403 de consulta para o estado "sem permissão" da tela', async () => {
    renderWithProviders(<FailingQuery error={new ApiError(403, { code: 'ACCESS_DENIED' })} />);

    expect(await screen.findByText('consulta falhou')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('403 de escrita vira toast: não há tela mostrando a ação negada', async () => {
    renderWithProviders(<FailingMutation error={new ApiError(403, { code: 'ACCESS_DENIED' })} />);

    fireEvent.click(screen.getByRole('button', { name: 'Disparar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Você não tem permissão para esta operação.',
    );
  });

  it('a tela que trata o erro marca suppressErrorToast e não recebe o aviso de novo', async () => {
    renderWithProviders(
      <FailingMutation
        error={new ApiError(400, { code: 'VALIDATION_ERROR' })}
        meta={{ suppressErrorToast: true }}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Disparar' }));

    expect(await screen.findByText('escrita falhou')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
