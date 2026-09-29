import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createCategory,
  deactivateCategory,
  listCategories,
  updateCategory,
  type CategoryRequest,
  type CategoryResponse,
} from '../api/categoriesApi';

/**
 * Chave da lista de categorias no cache; toda mutação do CRUD (1205) invalida por este prefixo.
 * As telas de produto (1204a/1204b) leem nome e opções do filtro **desta** query, então renová-la
 * já basta para elas — a lista de produtos em si não muda ao renomear ou desativar categoria.
 */
export const categoriesQueryKey = ['categories'] as const;

/**
 * Categorias para o filtro/coluna da lista de produtos (1204a) e para a tela de gestão (1205). O
 * erro aqui não ganha estado próprio: sem categorias a lista de produtos continua útil (coluna e
 * filtro ficam vazios) e o 403 da leitura é mostrado pela tela dona da query, não por esta.
 */
export function useCategories() {
  return useQuery({
    queryKey: categoriesQueryKey,
    queryFn: listCategories,
  });
}

/**
 * Escrita do formulário (POST/PUT): `suppressErrorToast` porque o próprio modal mostra o erro — o
 * 409 de nome em uso vira erro do campo `name`, e o toast global só repetiria a mensagem.
 */
function useCategoryFormMutation<TVariables>(
  mutationFn: (variables: TVariables) => Promise<CategoryResponse>,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    meta: { suppressErrorToast: true },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: categoriesQueryKey });
    },
  });
}

/** Cadastra a categoria (`category.write` no servidor). */
export function useCreateCategory() {
  return useCategoryFormMutation((body: CategoryRequest) => createCategory(body));
}

/** Edita a categoria (`category.write` no servidor); sem `If-Match` — o recurso não tem versão. */
export function useUpdateCategory() {
  return useCategoryFormMutation((variables: { id: string; body: CategoryRequest }) =>
    updateCategory(variables.id, variables.body),
  );
}

/**
 * Desativa a categoria (`category.write` no servidor). Aqui **não** se silencia o erro: o 404 de
 * quem repete o `DELETE` de uma categoria já desativada é justamente o aviso que o operador precisa
 * ler (toast global de `app/query-client`), e a lista é relida do servidor para não ficar mostrando
 * um estado que a escrita recusou.
 */
export function useDeactivateCategory() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: deactivateCategory,
    // Sucesso (204) ou 404 de quem repetiu um DELETE de categoria já desativada: nos dois casos a
    // lista é relida do servidor, que é quem diz o estado.
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: categoriesQueryKey });
    },
  });
}
