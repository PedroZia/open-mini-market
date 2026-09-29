import { describe, expect, it } from 'vitest';
import { isValidTaxId, normalizeTaxId } from './taxId';

/**
 * O helper é a cópia fiel do `TaxIdValidator` do servidor (`customers/application`): os mesmos
 * casos bons e ruins têm de valer aqui para a UI não aceitar o que o backend recusa — e vice-versa.
 */
describe('normalizeTaxId', () => {
  it('guarda só os dígitos, aceitando a máscara digitada', () => {
    expect(normalizeTaxId('529.982.247-25')).toBe('52998224725');
    expect(normalizeTaxId(' 529 982 247 25 ')).toBe('52998224725');
  });

  it('vazio, nulo ou sem nenhum dígito é "sem CPF"', () => {
    expect(normalizeTaxId('')).toBeNull();
    expect(normalizeTaxId('   ')).toBeNull();
    expect(normalizeTaxId(null)).toBeNull();
    expect(normalizeTaxId(undefined)).toBeNull();
    expect(normalizeTaxId('abc')).toBeNull();
  });
});

describe('isValidTaxId', () => {
  it('aceita CPF válido, com ou sem máscara', () => {
    expect(isValidTaxId('52998224725')).toBe(true);
    expect(isValidTaxId('529.982.247-25')).toBe(true);
  });

  it('recusa dígito verificador errado', () => {
    expect(isValidTaxId('529.982.247-24')).toBe(false);
    expect(isValidTaxId('111.444.777-34')).toBe(false);
  });

  it('recusa sequências de dígitos iguais, que passariam na conta', () => {
    expect(isValidTaxId('11111111111')).toBe(false);
    expect(isValidTaxId('000.000.000-00')).toBe(false);
  });

  it('recusa quantidade de dígitos diferente de onze', () => {
    expect(isValidTaxId('')).toBe(false);
    expect(isValidTaxId('5299822472')).toBe(false);
    expect(isValidTaxId('529982247250')).toBe(false);
    expect(isValidTaxId('abc')).toBe(false);
  });
});
