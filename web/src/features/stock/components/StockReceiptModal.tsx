import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm, type UseFormSetError } from 'react-hook-form';
import { z } from 'zod';
import { parseDecimalInput } from '../../../shared/lib/money';
import { errorMessage, fieldErrors } from '../../../shared/lib/problem';
import { formatQuantity } from '../../../shared/lib/quantity';
import { TextField } from '../../../shared/ui/FormFields';
import { Modal } from '../../../shared/ui/Modal';
import { useReceiveStock } from '../hooks/useStock';

/**
 * Entrada de mercadoria (1206b, `POST /stock/{productId}/receipts`, passo 706): repõe o saldo com
 * `PURCHASE_IN`. A quantidade é obrigatória e maior que zero; o custo unitário e o motivo são
 * opcionais — o custo, quando vem, atualiza o custo do produto no servidor. O saldo novo é do
 * servidor (BR-12): a tela mostra o saldo atual e não calcula o resultado.
 *
 * Operação de estoque idempotente por contrato (§8): o client manda a `Idempotency-Key` sozinho e o
 * recurso não exige `If-Match`.
 */

const receiptSchema = z.object({
  quantity: z
    .string()
    .trim()
    .refine((value) => parseDecimalInput(value, 3) !== null, 'Informe uma quantidade como 10 ou 2,5.')
    .refine((value) => parseDecimalInput(value, 3) !== 0, 'A quantidade precisa ser maior que zero.'),
  unitCost: z
    .string()
    .trim()
    .refine(
      (value) => value === '' || parseDecimalInput(value, 2) !== null,
      'Informe um custo como 5,25 ou deixe vazio.',
    ),
  reason: z.string().trim(),
});

type ReceiptFormValues = z.infer<typeof receiptSchema>;

/** Campos que aceitam `errors[]` do servidor; fora desta lista o erro fica no banner. */
const FORM_FIELDS: readonly (keyof ReceiptFormValues)[] = ['quantity', 'unitCost', 'reason'];

/** `true` quando o erro virou erro de campo; o banner fica só para o que não coube em campo. */
function applyServerErrors(failure: unknown, setError: UseFormSetError<ReceiptFormValues>): boolean {
  let mapped = false;
  for (const [field, message] of Object.entries(fieldErrors(failure))) {
    if ((FORM_FIELDS as readonly string[]).includes(field)) {
      setError(field as keyof ReceiptFormValues, { type: 'server', message });
      mapped = true;
    }
  }
  return mapped;
}

export interface StockReceiptModalProps {
  productId: string;
  /** Nome do produto só para a frase do saldo atual; o id é o que viaja. */
  productName: string;
  /** Saldo do servidor no momento em que o modal abriu — referência, nunca base de cálculo. */
  currentQuantity: number;
  onClose: () => void;
}

const secondaryButtonClassName =
  'min-h-10 rounded-md border border-line px-4 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none';

const primaryButtonClassName =
  'min-h-10 rounded-md bg-brand px-4 text-sm font-semibold text-white transition-colors duration-150 ease-out hover:bg-brand/90 disabled:cursor-not-allowed disabled:opacity-60 motion-reduce:transition-none';

export function StockReceiptModal({
  productId,
  productName,
  currentQuantity,
  onClose,
}: StockReceiptModalProps) {
  const receive = useReceiveStock();
  const [unmapped, setUnmapped] = useState<unknown>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<ReceiptFormValues>({
    resolver: zodResolver(receiptSchema),
    defaultValues: { quantity: '', unitCost: '', reason: '' },
  });

  const submit = handleSubmit((values) => {
    setUnmapped(null);
    const quantity = parseDecimalInput(values.quantity, 3) ?? 0;
    const unitCostText = values.unitCost.trim();
    const reasonText = values.reason.trim();
    receive.mutate(
      {
        productId,
        body: {
          // O schema já barrou quantidade inválida ou zero; o valor vai cru, sem arredondamento.
          quantity,
          unitCost: unitCostText === '' ? undefined : (parseDecimalInput(unitCostText, 2) ?? 0),
          reason: reasonText === '' ? undefined : reasonText,
        },
      },
      {
        onSuccess: onClose,
        onError: (failure) => {
          if (!applyServerErrors(failure, setError)) {
            setUnmapped(failure);
          }
        },
      },
    );
  });

  return (
    <Modal open title="Entrada de mercadoria" onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        noValidate
        onSubmit={(event) => {
          void submit(event);
        }}
      >
        <p className="text-ink-muted">
          Saldo atual de <strong className="text-ink">{productName}</strong>:{' '}
          <strong className="text-ink">{formatQuantity(currentQuantity)}</strong>. O saldo novo é
          calculado pelo servidor.
        </p>

        <TextField
          id="entrada-quantidade"
          label="Quantidade"
          field={register('quantity')}
          error={errors.quantity}
          inputMode="decimal"
          hint="Ex.: 10 ou 2,5."
        />

        <TextField
          id="entrada-custo"
          label="Custo unitário (R$)"
          field={register('unitCost')}
          error={errors.unitCost}
          inputMode="decimal"
          hint="Opcional; quando informado, atualiza o custo do produto."
        />

        <TextField
          id="entrada-motivo"
          label="Motivo"
          field={register('reason')}
          error={errors.reason}
          hint="Opcional; ex.: compra do fornecedor."
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
            disabled={receive.isPending}
            aria-busy={receive.isPending}
            className={primaryButtonClassName}
          >
            Registrar entrada
          </button>
        </div>
      </form>
    </Modal>
  );
}
