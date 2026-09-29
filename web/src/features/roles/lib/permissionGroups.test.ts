import { describe, expect, it } from 'vitest';
import { permissionGroups } from './permissionGroups';

/**
 * O catálogo de permissões (1208b) nasce da união das permissões de todos os papéis de
 * `GET /roles`, sem repetição, agrupado pelo prefixo antes do `.` e em ordem estável — é a
 * união que garante que o ADMIN efetivo ofereça todas as permissões na tela.
 */
describe('permissionGroups', () => {
  it('une as permissões de todos os papéis, sem repetir e em ordem alfabética', () => {
    const groups = permissionGroups([
      { code: 'ADMIN', permissions: ['user.write', 'product.read'] },
      { code: 'GERENTE', permissions: ['product.read', 'product.write'] },
      { code: 'OPERADOR', permissions: ['sale.create'] },
    ]);

    expect(groups).toEqual([
      { prefix: 'product', codes: ['product.read', 'product.write'] },
      { prefix: 'sale', codes: ['sale.create'] },
      { prefix: 'user', codes: ['user.write'] },
    ]);
  });

  it('código sem ponto vira grupo dele mesmo', () => {
    expect(permissionGroups([{ code: 'X', permissions: ['admin'] }])).toEqual([
      { prefix: 'admin', codes: ['admin'] },
    ]);
  });

  it('sem papéis ou sem permissões o catálogo é vazio', () => {
    expect(permissionGroups([])).toEqual([]);
    expect(permissionGroups([{ code: 'OPERADOR', permissions: [] }])).toEqual([]);
    expect(permissionGroups([{ code: 'OPERADOR' }])).toEqual([]);
  });
});
