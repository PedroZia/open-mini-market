import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { showToast } from '../../../shared/ui/Toast';
import {
  listRoles,
  replaceRolePermissions,
  type ReplaceRolePermissionsRequest,
} from '../api/rolesApi';

/**
 * Prefixo do cache do catálogo de papéis. É o mesmo `['roles']` que o formulário de usuário usa
 * (1208a) de propósito: os dois leem o mesmo recurso, então a invalidação feita aqui atualiza a
 * lista de papéis e as opções daquele formulário de uma vez.
 */
export const rolesQueryKey = ['roles'] as const;

/** Catálogo de papéis (`GET /roles`, `user.read`); a página e o modal da edição leem daqui. */
export function useRoles() {
  return useQuery({
    queryKey: rolesQueryKey,
    queryFn: listRoles,
  });
}

/**
 * Edição do mapa (`PUT /roles/{code}/permissions`, `role.write` no servidor). O modal mostra o
 * `problem+json` no banner, então o toast global de erro é silenciado; o sucesso avisa no toast e
 * relê a lista — quem diz o estado final é o servidor.
 *
 * O erro também relê: `UNKNOWN_PERMISSION` (400) e `ROLE_NOT_FOUND` (404) dizem que o retrato na
 * tela está velho (catálogo ou papel mudou em outra aba), e a tela deve mostrar o que o servidor
 * tem de verdade.
 */
export function useReplaceRolePermissions() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (variables: { code: string; body: ReplaceRolePermissionsRequest }) =>
      replaceRolePermissions(variables.code, variables.body),
    meta: { suppressErrorToast: true },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: rolesQueryKey });
      showToast('Permissões atualizadas.', 'success');
    },
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: rolesQueryKey });
    },
  });
}
