import { describe, expect, it } from 'vitest';
import { localDayRange } from './dayRange';

/**
 * Período do dia do dashboard (1212b): meia-noite local inclusiva e a do dia seguinte exclusiva, em
 * ISO-8601 com offset — o que o `sales-summary` exige.
 */

describe('localDayRange', () => {
  it('começa na meia-noite local e termina na do dia seguinte', () => {
    const range = localDayRange(new Date(2026, 8, 29, 15, 30, 0));
    expect(range.from).toBe(new Date(2026, 8, 29).toISOString());
    expect(range.to).toBe(new Date(2026, 8, 30).toISOString());
  });

  it('cobre a virada de mês e de ano', () => {
    const range = localDayRange(new Date(2026, 11, 31, 23, 59, 59));
    expect(range.from).toBe(new Date(2026, 11, 31).toISOString());
    expect(range.to).toBe(new Date(2027, 0, 1).toISOString());
  });

  it('não depende da hora de quem chama', () => {
    const start = localDayRange(new Date(2026, 8, 29, 0, 0, 0));
    expect(localDayRange(new Date(2026, 8, 29, 12, 0, 0))).toEqual(start);
    expect(localDayRange(new Date(2026, 8, 29, 23, 59, 59))).toEqual(start);
  });
});
