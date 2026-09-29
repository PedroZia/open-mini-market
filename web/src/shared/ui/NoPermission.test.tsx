import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { NoPermission } from './NoPermission';

describe('NoPermission', () => {
  it('explica o 403 com mensagem clara', () => {
    render(<NoPermission />);

    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Sem permissão');
    expect(alert).toHaveTextContent('Sua conta não tem permissão para acessar esta área.');
  });

  it('oferece nova tentativa quando a feature pede', () => {
    const onRetry = vi.fn();
    render(<NoPermission onRetry={onRetry} />);

    fireEvent.click(screen.getByRole('button', { name: 'Tentar de novo' }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});
