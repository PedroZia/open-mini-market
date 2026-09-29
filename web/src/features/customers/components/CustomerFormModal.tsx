import { zodResolver } from '@hookform/resolvers/zod';
import { ApiError } from '@minimarket/api-client';
import { useEffect, useState } from 'react';
import { useForm, type UseFormSetError } from 'react-hook-form';
import { z } from 'zod';
import { errorMessage, fieldErrors, isConcurrentModification } from '../../../shared/lib/problem';
import { isValidTaxId, normalizeTaxId } from '../../../shared/lib/taxId';
import { TextField } from '../../../shared/ui/FormFields';
import { Modal } from '../../../shared/ui/Modal';
import type { CustomerRequest, CustomerResponse } from '../api/customersApi';
import { useCreateCustomer, useCustomer, useUpdateCustomer } from '../hooks/useCustomers';

/**
 * Cadastro e edição de cliente (1207) num formulário só: `customer` ausente é cadastro e presente é
 * edição. No modo edição o registro é relido do servidor **ao abrir** (`GET /customers/{id}`) e o
 * formulário é semeado com ele — é o mesmo mecanismo que, depois de um 409
 * `CONCURRENT_MODIFICATION` (o lock otimista do repositório, já que o PUT não tem `If-Match`), relê o
 * cliente e mostra ao operador o que a outra edição gravou.
 *
 * A validação de CPF no cliente é conveniência (`shared/lib/taxId` espelha o `TaxIdValidator`); o
 * servidor revalida e é ele quem decide. Erro do servidor: `TAX_ID_ALREADY_EXISTS` (409, sem
 * `errors[]`) vira erro do campo `taxId` e o resto (400 `VALIDATION_ERROR` de CPF, 409 de
 * concorrência) vira o banner — nunca o toast global, que só repetiria a mensagem.
 */

const customerFormSchema = z.object({
  name: z.string().trim().min(1, 'Informe o nome.'),
  taxId: z
    .string()
    .trim()
    .refine(
      (value) => value === '' || isValidTaxId(value),
      'Informe um CPF válido ou deixe o campo vazio.',
    ),
  phone: z.string().trim(),
  email: z.string().trim(),
  notes: z.string().trim(),
});

type CustomerFormValues = z.infer<typeof customerFormSchema>;

const EMPTY_DEFAULTS: CustomerFormValues = {
  name: '',
  taxId: '',
  phone: '',
  email: '',
  notes: '',
};

/** Campos que aceitam `errors[]` do servidor; fora desta lista o erro não vira erro de campo. */
const FORM_FIELDS: readonly (keyof CustomerFormValues)[] = [
  'name',
  'taxId',
  'phone',
  'email',
  'notes',
];

/** Texto em branco não vira campo no corpo: no PUT, ausente limpa o valor. */
function optional(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed === '' ? undefined : trimmed;
}

/** Corpo do contrato com o CPF já em dígitos (o servidor normaliza de novo, mas não custa nada). */
function toBody(values: CustomerFormValues): CustomerRequest {
  return {
    name: values.name.trim(),
    taxId: normalizeTaxId(values.taxId) ?? undefined,
    phone: optional(values.phone),
    email: optional(values.email),
    notes: optional(values.notes),
  };
}

/** `true` quando o erro virou erro de campo; o banner fica só para o que não coube em campo. */
function applyServerErrors(
  failure: unknown,
  setError: UseFormSetError<CustomerFormValues>,
): boolean {
  let mapped = false;
  for (const [field, message] of Object.entries(fieldErrors(failure))) {
    if ((FORM_FIELDS as readonly string[]).includes(field)) {
      setError(field as keyof CustomerFormValues, { type: 'server', message });
      mapped = true;
    }
  }
  // CPF em uso chega sem `errors[]`: o `code` estável diz onde pintar a mensagem.
  if (!mapped && failure instanceof ApiError && failure.code === 'TAX_ID_ALREADY_EXISTS') {
    setError('taxId', { type: 'server', message: errorMessage(failure) });
    mapped = true;
  }
  return mapped;
}

export interface CustomerFormModalProps {
  /** Cliente em edição; ausente abre o cadastro. */
  customer?: CustomerResponse | undefined;
  onClose: () => void;
}

const secondaryButtonClassName =
  'min-h-10 rounded-md border border-line px-4 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none';

const primaryButtonClassName =
  'min-h-10 rounded-md bg-brand px-4 text-sm font-semibold text-white transition-colors duration-150 ease-out hover:bg-brand/90 disabled:cursor-not-allowed disabled:opacity-60 motion-reduce:transition-none';

export function CustomerFormModal({ customer, onClose }: CustomerFormModalProps) {
  const editing = customer !== undefined;
  const customerId = customer?.id;
  const detail = useCustomer(customerId ?? '', editing);
  const create = useCreateCustomer();
  const update = useUpdateCustomer();
  const [unmapped, setUnmapped] = useState<unknown>(null);

  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors },
  } = useForm<CustomerFormValues>({
    resolver: zodResolver(customerFormSchema),
    defaultValues: EMPTY_DEFAULTS,
  });

  // Semeia o formulário com o cliente lido no detalhe; depois de um 409 o refetch cai aqui de novo
  // e o operador passa a ver o registro que está no servidor, com o aviso na tela.
  useEffect(() => {
    const loaded = detail.data;
    if (loaded === undefined) {
      return;
    }
    reset({
      name: loaded.name ?? '',
      taxId: loaded.taxId ?? '',
      phone: loaded.phone ?? '',
      email: loaded.email ?? '',
      notes: loaded.notes ?? '',
    });
  }, [detail.data, reset]);

  const pending = editing ? update.isPending : create.isPending;

  const submit = handleSubmit((values) => {
    setUnmapped(null);
    const onError = (cause: unknown) => {
      if (!applyServerErrors(cause, setError)) {
        setUnmapped(cause);
      }
      if (isConcurrentModification(cause)) {
        // Outra edição gravou antes: relê o cliente para o próximo Salvar partir do que o servidor
        // tem, em vez de sobrescrever a edição alheia com a cópia velha da tela.
        void detail.refetch();
      }
    };

    if (customerId !== undefined) {
      update.mutate({ id: customerId, body: toBody(values) }, { onSuccess: onClose, onError });
      return;
    }
    create.mutate(toBody(values), { onSuccess: onClose, onError });
  });

  const title = editing ? 'Editar cliente' : 'Novo cliente';

  // O detalhe é a fonte dos campos: sem ele não há formulário para mostrar.
  if (editing && detail.isPending) {
    return (
      <Modal open title={title} onClose={onClose}>
        <p className="text-ink-muted">Carregando cliente…</p>
      </Modal>
    );
  }

  if (editing && detail.error !== null && detail.error !== undefined) {
    return (
      <Modal open title={title} onClose={onClose}>
        <p role="alert" className="font-medium text-danger">
          {errorMessage(detail.error)}
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
          id="cliente-nome"
          label="Nome"
          field={register('name')}
          error={errors.name}
        />

        <TextField
          id="cliente-cpf"
          label="CPF"
          field={register('taxId')}
          error={errors.taxId}
          inputMode="numeric"
          hint="Opcional; com ou sem máscara (o servidor valida de novo)."
        />

        <TextField
          id="cliente-telefone"
          label="Telefone"
          field={register('phone')}
          error={errors.phone}
          inputMode="numeric"
          hint="Opcional; usado na busca do caixa."
        />

        <TextField
          id="cliente-email"
          label="E-mail"
          field={register('email')}
          error={errors.email}
          hint="Opcional."
        />

        <TextField
          id="cliente-observacoes"
          label="Observações"
          field={register('notes')}
          error={errors.notes}
          hint="Opcional; o caixa vê esta anotação."
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
