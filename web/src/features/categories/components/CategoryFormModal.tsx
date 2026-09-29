import { zodResolver } from '@hookform/resolvers/zod';
import { ApiError } from '@minimarket/api-client';
import { useState } from 'react';
import { useForm, type UseFormSetError } from 'react-hook-form';
import { z } from 'zod';
import { SelectField, TextField } from '../../../shared/ui/FormFields';
import { Modal } from '../../../shared/ui/Modal';
import { errorMessage, fieldErrors } from '../../../shared/lib/problem';
import type { CategoryRequest, CategoryResponse } from '../api/categoriesApi';
import { useCreateCategory, useUpdateCategory } from '../hooks/useCategories';

/**
 * Cadastro e edição de categoria (1205) num formulário só: `category` ausente é cadastro e presente
 * é edição — a lista já traz todos os campos do recurso, então não há detalhe para reler. O `PUT`
 * não leva `If-Match` (o contrato de categoria não expõe `version`): o último a salvar vence.
 *
 * Nome em uso é 409 `CATEGORY_NAME_ALREADY_EXISTS` **sem `errors[]`** (é conflito de estado, não
 * erro de validação de forma): o aviso é pregado no campo `name` a partir do `code`; quando o
 * servidor mandar `errors[]`, vale a mensagem dele.
 */

/** Ordem como inteiro de 32 bits (o `sortOrder` do contrato é um `Integer`); `null` é inválida. */
function parseSortOrder(value: string): number | null {
  if (!/^-?\d+$/.test(value.trim())) {
    return null;
  }
  const parsed = Number(value.trim());
  return Number.isSafeInteger(parsed) && parsed >= -2_147_483_648 && parsed <= 2_147_483_647
    ? parsed
    : null;
}

const categoryFormSchema = z.object({
  name: z.string().trim().min(1, 'Informe o nome.'),
  parentId: z.string(),
  sortOrder: z
    .string()
    .trim()
    .refine(
      (value) => value === '' || parseSortOrder(value) !== null,
      'Informe a ordem como um número inteiro ou deixe vazio.',
    ),
});

type CategoryFormValues = z.infer<typeof categoryFormSchema>;

/** Campos que aceitam `errors[]` do servidor; fora desta lista o erro não vira erro de campo. */
const FORM_FIELDS: readonly (keyof CategoryFormValues)[] = ['name', 'parentId', 'sortOrder'];

/** Corpo do contrato: vazio é ausente — pai ausente é raiz e ordem ausente vale o default (0). */
function toBody(values: CategoryFormValues): CategoryRequest {
  const trimmedSortOrder = values.sortOrder.trim();
  return {
    name: values.name.trim(),
    parentId: values.parentId === '' ? undefined : values.parentId,
    sortOrder: trimmedSortOrder === '' ? undefined : (parseSortOrder(trimmedSortOrder) ?? 0),
  };
}

/** `true` quando o erro virou erro de campo; o banner fica só para o que não coube em campo. */
function applyServerErrors(
  failure: unknown,
  setError: UseFormSetError<CategoryFormValues>,
): boolean {
  let mapped = false;
  for (const [field, message] of Object.entries(fieldErrors(failure))) {
    if ((FORM_FIELDS as readonly string[]).includes(field)) {
      setError(field as keyof CategoryFormValues, { type: 'server', message });
      mapped = true;
    }
  }
  // Nome em uso chega sem `errors[]`: o `code` estável diz onde pintar a mensagem.
  if (!mapped && failure instanceof ApiError && failure.code === 'CATEGORY_NAME_ALREADY_EXISTS') {
    setError('name', { type: 'server', message: errorMessage(failure) });
    mapped = true;
  }
  return mapped;
}

export interface CategoryFormModalProps {
  /** Categoria em edição; ausente abre o cadastro. */
  category?: CategoryResponse | undefined;
  /** Categorias da lista: viram as opções de pai, sem a própria em edição. */
  categories: readonly CategoryResponse[];
  onClose: () => void;
}

const secondaryButtonClassName =
  'min-h-10 rounded-md border border-line px-4 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none';

const primaryButtonClassName =
  'min-h-10 rounded-md bg-brand px-4 text-sm font-semibold text-white transition-colors duration-150 ease-out hover:bg-brand/90 disabled:cursor-not-allowed disabled:opacity-60 motion-reduce:transition-none';

export function CategoryFormModal({ category, categories, onClose }: CategoryFormModalProps) {
  const editing = category !== undefined;
  const create = useCreateCategory();
  const update = useUpdateCategory();
  const [unmapped, setUnmapped] = useState<unknown>(null);

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<CategoryFormValues>({
    resolver: zodResolver(categoryFormSchema),
    defaultValues: {
      name: category?.name ?? '',
      parentId: category?.parentId ?? '',
      sortOrder: category?.sortOrder === undefined ? '' : String(category.sortOrder),
    },
  });

  const pending = editing ? update.isPending : create.isPending;
  // Hierarquia simples (sem árvore, como o passo pede): o pai é qualquer categoria menos ela mesma.
  const parents = categories.filter(
    (option) => option.id !== undefined && option.id !== category?.id,
  );

  const submit = handleSubmit((values) => {
    setUnmapped(null);
    const onError = (cause: unknown) => {
      if (!applyServerErrors(cause, setError)) {
        setUnmapped(cause);
      }
    };
    if (category?.id !== undefined) {
      update.mutate({ id: category.id, body: toBody(values) }, { onSuccess: onClose, onError });
      return;
    }
    create.mutate(toBody(values), { onSuccess: onClose, onError });
  });

  return (
    <Modal open title={editing ? 'Editar categoria' : 'Nova categoria'} onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        noValidate
        onSubmit={(event) => {
          void submit(event);
        }}
      >
        <TextField
          id="categoria-nome"
          label="Nome"
          field={register('name')}
          error={errors.name}
          hint="Único no catálogo; vale para produtos e vendas antigas."
        />

        <SelectField
          id="categoria-pai"
          label="Categoria pai"
          field={register('parentId')}
          error={errors.parentId}
          hint="Opcional; sem pai a categoria fica na raiz."
        >
          <option value="">Sem categoria pai</option>
          {parents.map((option) => (
            <option key={option.id} value={option.id}>
              {option.name ?? option.id}
            </option>
          ))}
        </SelectField>

        <TextField
          id="categoria-ordem"
          label="Ordem"
          field={register('sortOrder')}
          error={errors.sortOrder}
          inputMode="numeric"
          hint="Opcional; números menores aparecem primeiro (vazio vale 0)."
        />

        {unmapped !== null && unmapped !== undefined ? (
          <p
            role="alert"
            className="rounded-md border border-danger/40 bg-danger/5 px-3 py-2 font-medium text-danger"
          >
            {errorMessage(unmapped)}
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
