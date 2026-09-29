import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { isConcurrentModification } from '../../../shared/lib/problem';
import { showToast } from '../../../shared/ui/Toast';
import {
  createCustomer,
  disableCustomer,
  getCustomer,
  listCustomers,
  updateCustomer,
  type CustomerQuery,
  type CustomerRequest,
  type CustomerResponse,
} from '../api/customersApi';

/** Prefixo do cache de clientes; toda mutação invalida lista **e** detalhe. */
export const customersQueryKey = ['customers'] as const;

/** Chave do detalhe do cliente — filha de `customersQueryKey`, então a invalidação o alcança. */
function customerQueryKey(id: string) {
  return [...customersQueryKey, 'detail', id] as const;
}

/**
 * Lista de clientes do filtro corrente (1207). `placeholderData: keepPreviousData` mantém a página
 * anterior na tela enquanto a nova chega — trocar página ou busca não pisca o estado de carregando;
 * o `DataTable` marca a transição com "Atualizando…".
 */
export function useCustomers(query: CustomerQuery) {
  return useQuery({
    queryKey: [...customersQueryKey, query],
    queryFn: () => listCustomers(query),
    placeholderData: keepPreviousData,
  });
}

/**
 * Detalhe do cliente para o formulário de edição (1207). `staleTime: 0` porque o modal precisa do
 * registro como o servidor o tem **agora** — cada abertura relê e, depois de um 409, a releitura
 * mostra ao operador o que a outra edição gravou. Sem refetch por foco para uma leitura de fora não
 * reescrever um formulário aberto. `enabled` falso é o modo criação, que não lê nada.
 */
export function useCustomer(id: string, enabled = true) {
  return useQuery({
    queryKey: customerQueryKey(id),
    queryFn: () => getCustomer(id),
    enabled,
    staleTime: 0,
    refetchOnWindowFocus: false,
  });
}

/**
 * Escrita do formulário (1207): `suppressErrorToast` porque o próprio modal mostra o erro — o 409
 * de CPF em uso vira erro do campo `taxId` e o 400/409 restante vira o banner, então o toast global
 * só repetiria a mensagem. O sucesso invalida lista (e detalhe): quem diz o estado final é o
 * servidor, nunca o que o cliente montou.
 */
function useCustomerFormMutation<TVariables>(
  mutationFn: (variables: TVariables) => Promise<CustomerResponse>,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    meta: { suppressErrorToast: true },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: customersQueryKey });
    },
  });
}

/** Cadastra o cliente (`customer.write` no servidor). */
export function useCreateCustomer() {
  return useCustomerFormMutation((body: CustomerRequest) => createCustomer(body));
}

/** Edita o cliente (`customer.write` no servidor); sem `If-Match` — o recurso não o exige. */
export function useUpdateCustomer() {
  return useCustomerFormMutation((variables: { id: string; body: CustomerRequest }) =>
    updateCustomer(variables.id, variables.body),
  );
}

/**
 * Desativa o cliente (`customer.write` no servidor): toast de sucesso (não é erro, então o canal
 * global não o mostraria) e lista invalidada — a linha some porque a busca só devolve vivos. O erro
 * **não** é silenciado: o 404 de quem clica em desativar depois de outro operador é justamente o
 * aviso que precisa aparecer. O 409 `CONCURRENT_MODIFICATION` também relê a lista, para a tela
 * mostrar o registro como o servidor o deixou (mesmo tratamento do 1204b).
 */
export function useDisableCustomer() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: disableCustomer,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: customersQueryKey });
      showToast('Cliente desativado.', 'success');
    },
    onError: (error: unknown) => {
      if (isConcurrentModification(error)) {
        void queryClient.invalidateQueries({ queryKey: customersQueryKey });
      }
    },
  });
}
