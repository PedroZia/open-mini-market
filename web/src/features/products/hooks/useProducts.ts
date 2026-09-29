import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  changeProductPrice,
  createProduct,
  disableProduct,
  enableProduct,
  getProduct,
  listProducts,
  updateProduct,
  type ChangeProductPriceRequest,
  type CreateProductRequest,
  type ProductQuery,
  type ProductResponse,
  type UpdateProductRequest,
} from '../api/productsApi';

/** Prefixo do cache de produtos; toda mutação invalida tudo o que é lista **e** detalhe. */
export const productsQueryKey = ['products'] as const;

/** Chave do detalhe de um produto — filha de `productsQueryKey`, então a invalidação o alcança. */
function productQueryKey(id: string) {
  return [...productsQueryKey, 'detail', id] as const;
}

/**
 * Lista de produtos do filtro corrente (1204a). `placeholderData: keepPreviousData` mantém a
 * página anterior na tela enquanto a nova chega — trocar página ou filtro não pisca o estado de
 * carregando; o `DataTable` marca a transição com "Atualizando…".
 */
export function useProducts(query: ProductQuery) {
  return useQuery({
    queryKey: [...productsQueryKey, query],
    queryFn: () => listProducts(query),
    placeholderData: keepPreviousData,
  });
}

/**
 * Detalhe do produto para o formulário de edição. `staleTime: 0` porque o `version` do `If-Match`
 * precisa ser o do servidor no momento em que o modal abre (versão velha = 409 na cara do
 * operador); sem refetch por foco para uma leitura de fora não reescrever um formulário aberto.
 * `enabled` falso é o modo criação, que não lê nada.
 */
export function useProduct(id: string, enabled = true) {
  return useQuery({
    queryKey: productQueryKey(id),
    queryFn: () => getProduct(id),
    enabled,
    staleTime: 0,
    refetchOnWindowFocus: false,
  });
}

/**
 * Desativa/reativa com a lista invalidada: quem diz o estado final é o servidor, então a tela relê
 * a página corrente em vez de remendar o cache com o produto devolvido.
 */
function useProductStatusMutation(mutationFn: (id: string) => Promise<ProductResponse>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: productsQueryKey });
    },
  });
}

/** Desativa o produto (ação que exige `product.write` no servidor). */
export function useDisableProduct() {
  return useProductStatusMutation(disableProduct);
}

/** Reativa o produto (ação que exige `product.write` no servidor). */
export function useEnableProduct() {
  return useProductStatusMutation(enableProduct);
}

/**
 * Escrita do formulário de produto (405/410/411): invalida lista e detalhe — o estado que fica na
 * tela é o do servidor, nunca o que o cliente montou. O erro é mostrado pelo próprio formulário
 * (banner com a mensagem do `problem+json` e `errors[]` por campo), então o toast global duplicaria
 * a mensagem de um 409/400 que o modal já explica.
 */
function useProductFormMutation<TVariables>(
  mutationFn: (variables: TVariables) => Promise<ProductResponse>,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    meta: { suppressErrorToast: true },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: productsQueryKey });
    },
  });
}

/** Cadastra o produto (`product.write` no servidor). */
export function useCreateProduct() {
  return useProductFormMutation((body: CreateProductRequest) => createProduct(body));
}

/** Edita o cadastro com `If-Match` da versão lida no detalhe (`product.write` no servidor). */
export function useUpdateProduct() {
  return useProductFormMutation(
    (variables: { id: string; body: UpdateProductRequest; version: number }) =>
      updateProduct(variables.id, variables.body, variables.version),
  );
}

/** Altera o preço com motivo (`price.write` no servidor). */
export function useChangeProductPrice() {
  return useProductFormMutation((variables: { id: string; body: ChangeProductPriceRequest }) =>
    changeProductPrice(variables.id, variables.body),
  );
}
