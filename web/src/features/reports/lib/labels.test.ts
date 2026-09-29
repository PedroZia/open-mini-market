import { describe, expect, it } from 'vitest';
import { dayLabel, groupLabel, operatorLabel, paymentMethodLabel } from './labels';

/**
 * Rótulos dos relatórios (1212b): o código do servidor vira texto pt-BR sem deslocar data (a chave
 * do dia vem em UTC) e sem inventar nome para o que a tela não conhece.
 */

const DATE_FORMAT = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short' });

describe('labels dos relatórios', () => {
  it('traduz as cinco formas do contrato e mantém o código desconhecido como veio', () => {
    expect(paymentMethodLabel('CASH')).toBe('Dinheiro');
    expect(paymentMethodLabel('PIX')).toBe('PIX');
    expect(paymentMethodLabel('DEBIT')).toBe('Débito');
    expect(paymentMethodLabel('CREDIT')).toBe('Crédito');
    expect(paymentMethodLabel('VOUCHER')).toBe('Vale');
    expect(paymentMethodLabel('CHEQUE')).toBe('CHEQUE');
  });

  it('formata a chave do dia em pt-BR sem deslocar o fuso', () => {
    // A chave vem de um `to_char` em UTC: 28/09 é 28/09 em qualquer fuso de quem opera.
    expect(dayLabel('2026-09-28')).toBe(DATE_FORMAT.format(new Date(2026, 8, 28)));
    expect(dayLabel('2026-09-28')).toBe('28/09/2026');
  });

  it('devolve a chave como veio quando ela não é uma data do contrato', () => {
    expect(dayLabel('2026-09')).toBe('2026-09');
    expect(dayLabel('ontem')).toBe('ontem');
  });

  it('usa o nome do operador do mapa e cai no id curto sem ele', () => {
    const names = new Map([['0198f3b7-8e2f-7c4a-8d1e-000000000002', 'Bruno Lima']]);
    expect(operatorLabel('0198f3b7-8e2f-7c4a-8d1e-000000000002', names)).toBe('Bruno Lima');
    expect(operatorLabel('0198f3c9-1a3b-7d5c-9e2f-000000000003', names)).toBe('0198f3c9');
    expect(operatorLabel('0198f3c9-1a3b-7d5c-9e2f-000000000003', null)).toBe('0198f3c9');
    expect(operatorLabel(undefined, names)).toBe('—');
    expect(operatorLabel('', names)).toBe('—');
  });

  it('escolhe o rótulo pela dimensão do agrupamento', () => {
    expect(groupLabel('day', '2026-09-28', null)).toBe('28/09/2026');
    expect(groupLabel('operator', '0198f3c9-1a3b-7d5c-9e2f-000000000003', null)).toBe('0198f3c9');
    expect(groupLabel('paymentMethod', 'VOUCHER', null)).toBe('Vale');
  });
});
