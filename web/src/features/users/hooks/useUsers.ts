import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { isConflict } from '../../../shared/lib/problem';
import { showToast } from '../../../shared/ui/Toast';
import {
  createUser,
  disableUser,
  enableUser,
  listRoles,
  listUsers,
  resetPassword,
  revokeSessions,
  updateUser,
  type CreateUserRequest,
  type ResetPasswordRequest,
  type UpdateUserRequest,
  type UserQuery,
  type UserResponse,
} from '../api/usersApi';

/** Prefixo do cache de usuários; toda mutação invalida a lista. */
export const usersQueryKey = ['users'] as const;

/** Chave do catálogo de papéis: o formulário de usuário lê as opções daqui (1208b reusa). */
export const rolesQueryKey = ['roles'] as const;

/**
 * Lista de usuários do filtro corrente (1208a). `placeholderData: keepPreviousData` mantém a página
 * anterior na tela enquanto a nova chega — trocar página, filtro ou ordem não pisca o estado de
 * carregando; o `DataTable` marca a transição com "Atualizando…".
 */
export function useUsers(query: UserQuery) {
  return useQuery({
    queryKey: [...usersQueryKey, query],
    queryFn: () => listUsers(query),
    placeholderData: keepPreviousData,
  });
}

/**
 * Papéis para o formulário (`GET /roles`, `user.read`) — mesmo padrão de `useCategories`. O erro
 * aqui não ganha estado próprio: a tela dona da query mostra o 403 de leitura e o formulário
 * continua útil sem papéis (o servidor aceita lista vazia).
 */
export function useRoles() {
  return useQuery({
    queryKey: rolesQueryKey,
    queryFn: listRoles,
  });
}

/**
 * Escrita do formulário de usuário (criação/edição): `suppressErrorToast` porque o próprio modal
 * mostra o erro (banner do `problem+json` e `errors[]` por campo), então o toast global só
 * repetiria a mensagem. O sucesso invalida a lista: quem diz o estado final é o servidor.
 *
 * O 409 `CONFLICT` (último ADMIN ativo) também relê a lista: a recusa vem da regra de estado do
 * servidor e a tela pode estar mostrando um retrato velho de quem são os ADMINs.
 */
function useUserFormMutation<TVariables>(
  mutationFn: (variables: TVariables) => Promise<UserResponse>,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    meta: { suppressErrorToast: true },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: usersQueryKey });
    },
    onError: (error: unknown) => {
      if (isConflict(error)) {
        void queryClient.invalidateQueries({ queryKey: usersQueryKey });
      }
    },
  });
}

/** Cadastra o usuário (`user.write` no servidor). */
export function useCreateUser() {
  return useUserFormMutation((body: CreateUserRequest) => createUser(body));
}

/** Edita nome de exibição e papéis (`user.write` no servidor); username e senha não mudam aqui. */
export function useUpdateUser() {
  return useUserFormMutation((variables: { id: string; body: UpdateUserRequest }) =>
    updateUser(variables.id, variables.body),
  );
}

/**
 * Desativa/reativa (`user.write` no servidor). Sucesso avisa no toast (o canal global só mostra
 * erro) e relê a lista; erro **não** é silenciado — o 409 `CONFLICT` do último ADMIN ativo e o 404
 * de quem clica depois de outro operador precisam aparecer. Nos dois casos a lista é relida, para
 * a tela mostrar o registro como o servidor o deixou.
 */
function useUserStatusMutation(
  mutationFn: (id: string) => Promise<UserResponse>,
  successMessage: string,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: usersQueryKey });
      showToast(successMessage, 'success');
    },
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: usersQueryKey });
    },
  });
}

/** Desativa o usuário; o último ADMIN ativo é recusado com 409 `CONFLICT` pelo servidor. */
export function useDisableUser() {
  return useUserStatusMutation(disableUser, 'Usuário desativado.');
}

/** Reativa o usuário desativado. */
export function useEnableUser() {
  return useUserStatusMutation(enableUser, 'Usuário habilitado.');
}

/**
 * Reset de senha (`user.write` no servidor). O modal mostra o erro (senha curta, 404), então o
 * toast global é silenciado ali; o sucesso relê a lista — o `mustChangePassword` do usuário muda.
 */
export function useResetPassword() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (variables: { id: string; body: ResetPasswordRequest }) =>
      resetPassword(variables.id, variables.body),
    meta: { suppressErrorToast: true },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: usersQueryKey });
      showToast('Senha redefinida; o usuário vai trocá-la no próximo acesso.', 'success');
    },
  });
}

/**
 * Revoga as sessões vivas do usuário (`user.session.revoke` no servidor; 204). Sucesso avisa no
 * toast; erro (404/403) segue o canal global, porque não há formulário para mostrá-lo. A lista não
 * muda com a revogação, então não há o que invalidar.
 */
export function useRevokeSessions() {
  return useMutation({
    mutationFn: revokeSessions,
    onSuccess: () => {
      showToast('Sessões revogadas.', 'success');
    },
  });
}
