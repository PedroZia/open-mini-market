import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm, type UseFormSetError } from 'react-hook-form';
import { z } from 'zod';
import { errorMessage, fieldErrors } from '../../../shared/lib/problem';
import { formatQuantity } from '../../../shared/lib/quantity';
import { TextField } from '../../../shared/ui/FormFields';
import { Modal } from '../../../shared/ui/Modal';
import { useAdjustStock } from '../hooks/useStock';

/**
 * Ajuste manual de estoque (1206b, `POST /stock/{productId}/adjustments`, passo 705): o operador
 * informa a **diferença** — positiva soma, negativa reduz — e o motivo obrigatório, porque o ajuste
 * é auditado. O saldo novo é do servidor (BR-12/BR-13): a tela mostra o saldo atual e não calcula o
 * resultado; quem recusa o saldo negativo da loja sem estoque negativo é o 422 `INSUFFICIENT_STOCK`.
 *
 * Operação de estoque idempotente por contrato (§8): o client manda a `Idempotency-Key` sozinho e o
 * recurso não exige `If-Match`.
 */

/** Delta com escala 3 (§4.4): aceita `-2,5`, `3` e `0,001`; quem valida de novo é o servidor. */
function parseSignedQuantity(input: string): number | null {
  const text = input.trim().replace(',', '.');
  if (!/^-?\d+(\.\d{1,3})?$/.test(text)) {
    return null;
  }
  const value = Number(text);
  return Number.isFinite(value) ? value : null;
}

const adjustmentSchema = z.object({
  quantityDelta: z
    .string()
    .trim()
    .refine((value) => parseSignedQuantity(value) !== null, 'Informe o delta como -2,5 ou 3.')
    .refine((value) => parseSignedQuantity(value) !== 0, 'O delta não pode ser zero.'),
  reason: z.string().trim().min(1, 'Informe o motivo do ajuste.'),
});

type AdjustmentFormValues = z.infer<typeof adjustmentSchema>;

/** Campos que aceitam `errors[]` do servidor; fora desta lista o erro fica no banner. */
const FORM_FIELDS: readonly (keyof AdjustmentFormValues)[] = ['quantityDelta', 'reason'];

/** `true` quando o erro virou erro de campo; o banner fica só para o que não coube em campo. */
function applyServerErrors(
  failure: unknown,
  setError: UseFormSetError<AdjustmentFormValues>,
): boolean {
  let mapped = false;
  for (const [field, message] of Object.entries(fieldErrors(failure))) {
    if ((FORM_FIELDS as readonly string[]).includes(field)) {
      setError(field as keyof AdjustmentFormValues, { type: 'server', message });
      mapped = true;
    }
  }
  return mapped;
}

export interface StockAdjustmentModalProps {
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

export function StockAdjustmentModal({
  productId,
  productName,
  currentQuantity,
  onClose,
}: StockAdjustmentModalProps) {
  const adjust = useAdjustStock();
  const [unmapped, setUnmapped] = useState<unknown>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<AdjustmentFormValues>({
    resolver: zodResolver(adjustmentSchema),
    defaultValues: { quantityDelta: '', reason: '' },
  });

  const submit = handleSubmit((values) => {
    setUnmapped(null);
    adjust.mutate(
      {
        productId,
        body: {
          // O schema já barrou delta inválido ou zero; o número vai cru, sem arredondamento.
          quantityDelta: parseSignedQuantity(values.quantityDelta) ?? 0,
          reason: values.reason.trim(),
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
    <Modal open title="Ajustar estoque" onClose={onClose}>
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
          calculado pelo servidor — informe só a diferença.
        </p>

        <TextField
          id="ajuste-delta"
          label="Quantidade (delta)"
          field={register('quantityDelta')}
          error={errors.quantityDelta}
          inputMode="decimal"
          hint="Positivo soma, negativo reduz. Ex.: -2,5."
        />

        <TextField
          id="ajuste-motivo"
          label="Motivo"
          field={register('reason')}
          error={errors.reason}
          hint="Ex.: ajuste de inventário."
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
            disabled={adjust.isPending}
            aria-busy={adjust.isPending}
            className={primaryButtonClassName}
          >
            Registrar ajuste
          </button>
        </div>
      </form>
    </Modal>
  );
}
