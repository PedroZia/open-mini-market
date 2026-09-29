import { describe, expect, it } from 'vitest';
import { formatDateTime, localDateTimeToInstant } from './datetime';

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

describe('localDateTimeToInstant', () => {
  it('converte o valor do datetime-local (hora local) no ISO com offset', () => {
    const value = '2026-09-28T08:00';

    expect(localDateTimeToInstant(value)).toBe(new Date(value).toISOString());
    // O que sai daqui é o formato com offset que o servidor aceita nos filtros de período.
    expect(localDateTimeToInstant(value)).toMatch(/(Z|[+-]\d{2}:\d{2})$/);
  });

  it('devolve undefined para vazio ou texto inválido — filtro em branco não vira parâmetro', () => {
    expect(localDateTimeToInstant('')).toBeUndefined();
    expect(localDateTimeToInstant('   ')).toBeUndefined();
    expect(localDateTimeToInstant('não é data')).toBeUndefined();
  });
});
