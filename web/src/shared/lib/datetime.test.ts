import { describe, expect, it } from 'vitest';
import { formatDateTime } from './datetime';

describe('formatDateTime', () => {
  it('formata o instante do contrato em pt-BR no fuso local', () => {
    const instant = '2026-09-28T22:21:54Z';
    const expected = new Intl.DateTimeFormat('pt-BR', {
      dateStyle: 'short',
      timeStyle: 'short',
    }).format(new Date(instant));

    expect(formatDateTime(instant)).toBe(expected);
    // O texto pt-BR separa dia/mês/ano e traz hora — não é o ISO cru do servidor.
    expect(formatDateTime(instant)).toMatch(/^\d{2}\/\d{2}\/\d{4}/);
  });

  it('mostra — para instante inválido, sem quebrar a tela', () => {
    expect(formatDateTime('não é data')).toBe('—');
  });
});
