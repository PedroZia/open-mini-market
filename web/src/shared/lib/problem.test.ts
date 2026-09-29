import { ApiError, ApiNetworkError, ApiTimeoutError } from '@minimarket/api-client';
import { describe, expect, it } from 'vitest';
import {
  errorMessage,
  fieldErrors,
  isCashSessionAlreadyClosed,
  isCashSessionNotOpen,
  isConcurrentModification,
  isConflict,
  isForbidden,
  isSaleAlreadyCompleted,
  isSessionHasOpenSales,
  isUnauthorized,
} from './problem';

describe('errorMessage', () => {
  it('usa a mensagem do código estável, não o detalhe do servidor', () => {
    const error = new ApiError(403, {
      code: 'ACCESS_DENIED',
      title: 'Acesso negado',
      detail: 'missing authority product.write',
    });

    expect(errorMessage(error)).toBe('Você não tem permissão para esta operação.');
  });

  it('403 tem mensagem clara e sem detalhe técnico', () => {
    const message = errorMessage(new ApiError(403, { code: 'ACCESS_DENIED', detail: 'missing authority' }));

    expect(message).toMatch(/permissão/i);
    expect(message).not.toContain('missing authority');
  });

  it('registro que sumiu vira recado para atualizar a lista', () => {
    const error = new ApiError(404, { code: 'PRODUCT_NOT_FOUND', detail: 'produto 019... não encontrado' });

    expect(errorMessage(error)).toBe('O registro não existe mais. Atualize a lista.');
  });

  it('sem tradução própria, mostra o detail escrito para humano', () => {
    const error = new ApiError(422, {
      code: 'PRODUCT_INACTIVE',
      detail: 'O produto está desativado.',
    });

    expect(errorMessage(error)).toBe('O produto está desativado.');
  });

  it('estoque insuficiente vira recado claro, sem vazar o detalhe técnico do servidor', () => {
    const error = new ApiError(422, {
      code: 'INSUFFICIENT_STOCK',
      detail: 'produto 019... ficaria com saldo -1.5 (delta -3) e a loja não permite estoque negativo',
    });

    const message = errorMessage(error);

    expect(message).toMatch(/estoque insuficiente/i);
    expect(message).not.toContain('019...');
  });

  it('não vaza detalhe técnico de 5xx', () => {
    const error = new ApiError(500, {
      code: 'INTERNAL_ERROR',
      detail: 'java.lang.IllegalStateException: pool exhausted',
    });

    expect(errorMessage(error)).toBe('O servidor falhou ao responder. Tente de novo em instantes.');
  });

  it('resposta fora do contrato não devolve o HTTP cru do client', () => {
    expect(errorMessage(new ApiError(502, {}))).toBe('A resposta do servidor veio fora do padrão. Tente de novo.');
  });

  it('cobre timeout, rede e falha inesperada', () => {
    expect(errorMessage(new ApiTimeoutError(10_000))).toContain('não respondeu a tempo');
    expect(errorMessage(new ApiNetworkError(new Error('offline')))).toContain(
      'Não foi possível falar com o servidor',
    );
    expect(errorMessage(new Error('boom'))).toBe('Não foi possível concluir a operação. Tente de novo.');
  });
});

describe('fieldErrors', () => {
  it('mapeia errors[] por campo para o formulário', () => {
    const error = new ApiError(400, {
      code: 'VALIDATION_ERROR',
      errors: [
        { field: 'name', message: 'não pode ser vazio' },
        { field: 'price', message: 'deve ser maior que zero' },
        { field: 'name', message: 'já existe' },
      ],
    });

    expect(fieldErrors(error)).toEqual({
      name: 'não pode ser vazio',
      price: 'deve ser maior que zero',
    });
  });

  it('vazio quando o erro não trouxe validação de campo', () => {
    expect(fieldErrors(new ApiError(500, {}))).toEqual({});
    expect(fieldErrors(new Error('boom'))).toEqual({});
  });
});

describe('isUnauthorized e isForbidden', () => {
  it('separam o que é da sessão (401) do que é permissão (403)', () => {
    expect(isUnauthorized(new ApiError(401, { code: 'SESSION_EXPIRED' }))).toBe(true);
    expect(isForbidden(new ApiError(403, { code: 'ACCESS_DENIED' }))).toBe(true);
    expect(isUnauthorized(new ApiError(403, { code: 'ACCESS_DENIED' }))).toBe(false);
    expect(isForbidden(new Error('boom'))).toBe(false);
  });
});

describe('isConcurrentModification', () => {
  it('reconhece o 409 de versão velha e não confunde com os outros conflitos', () => {
    expect(isConcurrentModification(new ApiError(409, { code: 'CONCURRENT_MODIFICATION' }))).toBe(true);
    expect(isConcurrentModification(new ApiError(409, { code: 'BARCODE_ALREADY_EXISTS' }))).toBe(false);
    expect(isConcurrentModification(new Error('boom'))).toBe(false);
  });
});

describe('isConflict', () => {
  it('reconhece a recusa de estado do servidor (último ADMIN ativo) e só ela', () => {
    expect(isConflict(new ApiError(409, { code: 'CONFLICT' }))).toBe(true);
    expect(isConflict(new ApiError(409, { code: 'CONCURRENT_MODIFICATION' }))).toBe(false);
    expect(isConflict(new Error('boom'))).toBe(false);
  });
});

describe('isSaleAlreadyCompleted', () => {
  it('reconhece o 409 da venda concluída e não confunde com os outros conflitos', () => {
    expect(isSaleAlreadyCompleted(new ApiError(409, { code: 'SALE_ALREADY_COMPLETED' }))).toBe(
      true,
    );
    expect(isSaleAlreadyCompleted(new ApiError(409, { code: 'CONFLICT' }))).toBe(false);
    expect(isSaleAlreadyCompleted(new Error('boom'))).toBe(false);
  });

  it('não vaza o id e o instante do detalhe técnico do servidor', () => {
    const error = new ApiError(409, {
      code: 'SALE_ALREADY_COMPLETED',
      detail: 'venda 0198f3a2-4c1d-7a2e-9b3f-000000000001 já foi concluída em 2026-09-28T23:00:00Z',
    });

    const message = errorMessage(error);

    expect(message).toMatch(/já foi concluída/i);
    expect(message).not.toContain('0198f3a2');
    expect(message).not.toContain('2026-09-28T23:00:00Z');
  });
});

describe('isCashSessionNotOpen', () => {
  it('reconhece o 404 do caixa fechado e não confunde com outros erros', () => {
    expect(isCashSessionNotOpen(new ApiError(404, { code: 'CASH_SESSION_NOT_OPEN' }))).toBe(true);
    expect(isCashSessionNotOpen(new ApiError(404, { code: 'CASH_SESSION_NOT_FOUND' }))).toBe(false);
    expect(isCashSessionNotOpen(new Error('boom'))).toBe(false);
  });
});

describe('movimentos de caixa (1210b)', () => {
  const sessionId = '0198f5b2-5e6f-7a81-9c13-000000000002';

  it('caixa sem sessão aberta explica o estado em vez de dizer que o registro sumiu', () => {
    const error = new ApiError(404, {
      code: 'CASH_SESSION_NOT_OPEN',
      detail: `caixa ${sessionId} não tem sessão aberta`,
    });

    const message = errorMessage(error);

    expect(message).toMatch(/não tem sessão aberta/i);
    expect(message).not.toContain(sessionId);
  });

  it('sessão já fechada e venda em andamento têm recado claro, sem o id do servidor', () => {
    const closed = new ApiError(409, {
      code: 'CASH_SESSION_ALREADY_CLOSED',
      detail: `sessão ${sessionId} já foi fechada em 2026-09-29T10:00:00Z`,
    });
    const openSales = new ApiError(409, {
      code: 'SESSION_HAS_OPEN_SALES',
      detail: `sessão ${sessionId} tem venda em andamento`,
    });

    expect(errorMessage(closed)).toMatch(/já foi fechada/i);
    expect(errorMessage(closed)).not.toContain(sessionId);

    expect(errorMessage(openSales)).toMatch(/venda em andamento/i);
    expect(errorMessage(openSales)).toMatch(/finalize ou cancele a venda/i);
    expect(errorMessage(openSales)).not.toContain(sessionId);
  });
});

describe('isCashSessionAlreadyClosed e isSessionHasOpenSales', () => {
  it('reconhecem cada 409 do fechamento e não confundem com outros conflitos', () => {
    expect(
      isCashSessionAlreadyClosed(new ApiError(409, { code: 'CASH_SESSION_ALREADY_CLOSED' })),
    ).toBe(true);
    expect(
      isCashSessionAlreadyClosed(new ApiError(409, { code: 'SESSION_HAS_OPEN_SALES' })),
    ).toBe(false);
    expect(isSessionHasOpenSales(new ApiError(409, { code: 'SESSION_HAS_OPEN_SALES' }))).toBe(
      true,
    );
    expect(isSessionHasOpenSales(new ApiError(404, { code: 'CASH_SESSION_NOT_OPEN' }))).toBe(
      false,
    );
    expect(isCashSessionAlreadyClosed(new Error('boom'))).toBe(false);
    expect(isSessionHasOpenSales(new Error('boom'))).toBe(false);
  });
});
