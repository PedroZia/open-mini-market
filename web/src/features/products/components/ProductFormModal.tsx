import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm, type UseFormSetError } from 'react-hook-form';
import { z } from 'zod';
import { parseDecimalInput } from '../../../shared/lib/money';
import { errorMessage, fieldErrors, isConcurrentModification } from '../../../shared/lib/problem';
import { Modal } from '../../../shared/ui/Modal';
import { useCategories } from '../../categories/hooks/useCategories';
import type { CreateProductRequest, UpdateProductRequest } from '../api/productsApi';
import { useCreateProduct, useProduct, useUpdateProduct } from '../hooks/useProducts';
import { SelectField, TextField } from './ProductFields';

/**
 * Cadastro e edição do produto (1204b) num formulário só: campos e validação iguais, `productId`
 * ausente é cadastro e presente é edição. No modo edição o barcode é somente leitura (o passo 410
 * não o aceita) e o preço não aparece — quem muda preço é o `PriceModal`, via `PATCH .../price`
 * (passo 411). O `If-Match` sai do `version` lido no detalhe **ao abrir** o modal (cada abertura relê
 * o produto) e versão velha → 409 `CONCURRENT_MODIFICATION`, que mostra a mensagem e relê de novo
 * em vez de sobrescrever a edição do outro (§9.4).
 */

/** Unidades comerciais do MVP (§5.3); o servidor valida a whitelist de novo. */
const UNITS = ['UN', 'KG'] as const;

/**
 * O formulário carrega os campos dos dois modos: `price` só é renderizado no cadastro (mas é
 * semeado com o preço do servidor na edição, então nunca bloqueia o submit) e `barcode` é
 * somente leitura na edição.
 */
const productFormSchema = z.object({
  name: z.string().trim().min(1, 'Informe o nome.'),
  barcode: z.string().trim(),
  internalCode: z.string().trim(),
  description: z.string().trim(),
  categoryId: z.string(),
  unit: z.enum(UNITS),
  price: z
    .string()
    .trim()
    .refine((value) => parseDecimalInput(value, 2) !== null, 'Informe um preço como 12,50.'),
  minQuantity: z
    .string()
    .trim()
    .refine(
      (value) => value === '' || parseDecimalInput(value, 3) !== null,
      'Informe uma quantidade como 1,5 ou deixe vazio.',
    ),
});

type ProductFormValues = z.infer<typeof productFormSchema>;

const CREATE_DEFAULTS: ProductFormValues = {
  name: '',
  barcode: '',
  internalCode: '',
  description: '',
  categoryId: '',
  unit: 'UN',
  price: '',
  minQuantity: '',
};

/** Campos que aceitam `errors[]` do servidor; fora desta lista o erro não vira erro de campo. */
const FORM_FIELDS: readonly (keyof ProductFormValues)[] = [
  'name',
  'barcode',
  'internalCode',
  'description',
  'categoryId',
  'unit',
  'price',
  'minQuantity',
];

/** Texto em branco não vira campo no corpo: no PUT, ausente e nulo limpam o valor (§410). */
function optional(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed === '' ? undefined : trimmed;
}

/** Quantidade mínima vazia é "sem mínimo"; o schema já garantiu o formato do resto. */
function optionalQuantity(value: string): number | undefined {
  return value.trim() === '' ? undefined : (parseDecimalInput(value, 3) ?? 0);
}

function createBody(values: ProductFormValues): CreateProductRequest {
  return {
    name: values.name.trim(),
    barcode: optional(values.barcode),
    internalCode: optional(values.internalCode),
    description: optional(values.description),
    categoryId: optional(values.categoryId),
    unit: values.unit,
    // O schema já barrou preço inválido; o valor vai cru, sem arredondamento (BR-12).
    price: parseDecimalInput(values.price, 2) ?? 0,
    minQuantity: optionalQuantity(values.minQuantity),
  };
}

function updateBody(values: ProductFormValues): UpdateProductRequest {
  return {
    name: values.name.trim(),
    internalCode: optional(values.internalCode),
    categoryId: optional(values.categoryId),
    unit: values.unit,
    description: optional(values.description),
    minQuantity: optionalQuantity(values.minQuantity),
  };
}

/** `errors[]` do `problem+json` no campo correspondente, quando o campo é deste formulário. */
function applyServerErrors(
  failure: unknown,
  setError: UseFormSetError<ProductFormValues>,
): void {
  for (const [field, message] of Object.entries(fieldErrors(failure))) {
    if ((FORM_FIELDS as readonly string[]).includes(field)) {
      setError(field as keyof ProductFormValues, { type: 'server', message });
    }
  }
}

export interface ProductFormModalProps {
  /** Produto em edição; ausente abre o cadastro. */
  productId?: string | undefined;
  onClose: () => void;
}

const secondaryButtonClassName =
  'min-h-10 rounded-md border border-line px-4 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none';

const primaryButtonClassName =
  'min-h-10 rounded-md bg-brand px-4 text-sm font-semibold text-white transition-colors duration-150 ease-out hover:bg-brand/90 disabled:cursor-not-allowed disabled:opacity-60 motion-reduce:transition-none';

export function ProductFormModal({ productId, onClose }: ProductFormModalProps) {
  const editing = productId !== undefined;
  const product = useProduct(productId ?? '', editing);
  const create = useCreateProduct();
  const update = useUpdateProduct();
  const categories = useCategories();

  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors },
  } = useForm<ProductFormValues>({
    resolver: zodResolver(productFormSchema),
    defaultValues: CREATE_DEFAULTS,
  });

  // Semeia o formulário com o produto lido no detalhe; depois de um 409 o refetch cai aqui de novo
  // e o operador passa a ver a versão que está no servidor, com o aviso na tela.
  useEffect(() => {
    const loaded = product.data;
    if (loaded === undefined) {
      return;
    }
    reset({
      name: loaded.name ?? '',
      barcode: loaded.barcode ?? '',
      internalCode: loaded.internalCode ?? '',
      description: loaded.description ?? '',
      categoryId: loaded.categoryId ?? '',
      unit: loaded.unit === 'KG' ? 'KG' : 'UN',
      price: loaded.price === undefined ? '' : String(loaded.price),
      minQuantity: loaded.minQuantity === undefined ? '' : String(loaded.minQuantity),
    });
  }, [product.data, reset]);

  const failure = editing ? update.error : create.error;
  const pending = editing ? update.isPending : create.isPending;

  const submit = handleSubmit((values) => {
    function onError(cause: unknown) {
      applyServerErrors(cause, setError);
      if (isConcurrentModification(cause)) {
        // Versão velha: relê o produto para o próximo Salvar levar a versão atual do servidor.
        void product.refetch();
      }
    }

    if (productId !== undefined && product.data !== undefined) {
      update.mutate(
        { id: productId, body: updateBody(values), version: product.data.version ?? 0 },
        { onSuccess: onClose, onError },
      );
      return;
    }
    create.mutate(createBody(values), { onSuccess: onClose, onError });
  });

  const title = editing ? 'Editar produto' : 'Novo produto';

  // O detalhe é a fonte do `version` e dos campos: sem ele não há formulário para mostrar.
  if (editing && product.isPending) {
    return (
      <Modal open title={title} onClose={onClose}>
        <p className="text-ink-muted">Carregando produto…</p>
      </Modal>
    );
  }

  if (editing && product.error !== null && product.error !== undefined) {
    return (
      <Modal open title={title} onClose={onClose}>
        <p role="alert" className="font-medium text-danger">
          {errorMessage(product.error)}
        </p>
      </Modal>
    );
  }

  return (
    <Modal open title={title} onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        noValidate
        onSubmit={(event) => {
          void submit(event);
        }}
      >
        <TextField
          id="produto-nome"
          label="Nome"
          field={register('name')}
          error={errors.name}
        />

        <TextField
          id="produto-barcode"
          label="Código de barras"
          field={register('barcode')}
          error={errors.barcode}
          readOnly={editing}
          hint={
            editing
              ? 'Não muda depois do cadastro.'
              : 'Opcional; o leitor do PDV resolve o produto por ele.'
          }
        />

        <TextField
          id="produto-codigo-interno"
          label="Código interno"
          field={register('internalCode')}
          error={errors.internalCode}
          hint="Opcional; código da etiqueta da balança."
        />

        <SelectField
          id="produto-categoria"
          label="Categoria"
          field={register('categoryId')}
          error={errors.categoryId}
        >
          <option value="">Sem categoria</option>
          {categories.data?.map((category) =>
            category.id === undefined ? null : (
              <option key={category.id} value={category.id}>
                {category.name ?? category.id}
              </option>
            ),
          )}
        </SelectField>

        <SelectField
          id="produto-unidade"
          label="Unidade"
          field={register('unit')}
          error={errors.unit}
        >
          <option value="UN">UN — unidade</option>
          <option value="KG">KG — quilo</option>
        </SelectField>

        {editing ? null : (
          <TextField
            id="produto-preco"
            label="Preço (R$)"
            field={register('price')}
            error={errors.price}
            inputMode="decimal"
            hint="Ex.: 12,50."
          />
        )}

        <TextField
          id="produto-quantidade-minima"
          label="Quantidade mínima"
          field={register('minQuantity')}
          error={errors.minQuantity}
          inputMode="decimal"
          hint="Opcional; referência para o aviso de estoque baixo."
        />

        <TextField
          id="produto-descricao"
          label="Descrição"
          field={register('description')}
          error={errors.description}
          hint="Opcional."
        />

        {failure !== null && failure !== undefined ? (
          <p
            role="alert"
            className="rounded-md border border-danger/40 bg-danger/5 px-3 py-2 font-medium text-danger"
          >
            {errorMessage(failure)}
          </p>
        ) : null}

        <div className="flex justify-end gap-2">
          <button type="button" onClick={onClose} className={secondaryButtonClassName}>
            Cancelar
          </button>
          <button
            type="submit"
            disabled={pending}
            aria-busy={pending}
            className={primaryButtonClassName}
          >
            Salvar
          </button>
        </div>
      </form>
    </Modal>
  );
}
