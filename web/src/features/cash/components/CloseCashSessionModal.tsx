import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm, type UseFormSetError } from 'react-hook-form';
import { z } from 'zod';
import { parseDecimalInput } from '../../../shared/lib/money';
import { errorMessage, fieldErrors } from '../../../shared/lib/problem';
import { TextField } from '../../../shared/ui/FormFields';
import { Modal } from '../../../shared/ui/Modal';
import { useCloseCashSession } from '../hooks/useCash';

/**
 * Fechamento do caixa (1210b, `POST /cash-registers/{id}/close`, passos 611/612): o operador informa
 * o valor contado e, se quiser, as observações — o esperado, a diferença e a situação da sessão são
 * do servidor (BR-12) e a tela não projeta nenhum deles. Valor contado zero é válido (a gaveta pode
 * estar vazia); negativo ou não numérico é recusado de novo pelo servidor.
 *
 * O 409 `SESSION_HAS_OPEN_SALES` (venda em andamento) e o `CASH_SESSION_ALREADY_CLOSED` chegam com o
 * texto do mapeador de `problem+json` — sem o id da sessão do `detail` — e o hook relê o caixa,
 * porque a situação na tela estava velha.
 */

/** Valor em reais com a escala 2 do contrato; a conversão é de entrada, nunca de cálculo (BR-12). */
function parseAmount(input: string): number | null {
  return parseDecimalInput(input, 2);
}

const closeSchema = z.object({
  countedAmount: z
    .string()
    .trim()
    .refine((value) => parseAmount(value) !== null, 'Informe o valor contado. Ex.: 150,00.'),
  notes: z.string().trim(),
});

type CloseFormValues = z.infer<typeof closeSchema>;

/** Campos que aceitam `errors[]` do servidor; fora desta lista o erro fica no banner. */
const FORM_FIELDS: readonly (keyof CloseFormValues)[] = ['countedAmount', 'notes'];

/** `true` quando o erro virou erro de campo; o banner fica só para o que não coube em campo. */
function applyServerErrors(failure: unknown, setError: UseFormSetError<CloseFormValues>): boolean {
  let mapped = false;
  for (const [field, message] of Object.entries(fieldErrors(failure))) {
    if ((FORM_FIELDS as readonly string[]).includes(field)) {
      setError(field as keyof CloseFormValues, { type: 'server', message });
      mapped = true;
    }
  }
  return mapped;
}

export interface CloseCashSessionModalProps {
  cashRegisterId: string;
  onClose: () => void;
}

const secondaryButtonClassName =
  'min-h-10 rounded-md border border-line px-4 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none';

const primaryButtonClassName =
  'min-h-10 rounded-md bg-brand px-4 text-sm font-semibold text-white transition-colors duration-150 ease-out hover:bg-brand/90 disabled:cursor-not-allowed disabled:opacity-60 motion-reduce:transition-none';

export function CloseCashSessionModal({ cashRegisterId, onClose }: CloseCashSessionModalProps) {
  const close = useCloseCashSession();
  const [unmapped, setUnmapped] = useState<unknown>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<CloseFormValues>({
    resolver: zodResolver(closeSchema),
    defaultValues: { countedAmount: '', notes: '' },
  });

  const submit = handleSubmit((values) => {
    setUnmapped(null);
    const notes = values.notes.trim();
    close.mutate(
      {
        cashRegisterId,
        body: {
          // O schema já barrou valor inválido; o número vai cru, sem arredondamento.
          countedAmount: parseAmount(values.countedAmount) ?? 0,
          // Observação em branco não viaja: o campo é opcional no contrato.
          notes: notes === '' ? undefined : notes,
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
    <Modal open title="Fechar caixa" onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        noValidate
        onSubmit={(event) => {
          void submit(event);
        }}
      >
        <p className="text-ink-muted">
          Confira o dinheiro da gaveta. O esperado e a diferença são calculados pelo servidor — a
          tela não projeta nenhum dos dois.
        </p>

        <TextField
          id="caixa-fechamento-contado"
          label="Valor contado (R$)"
          field={register('countedAmount')}
          error={errors.countedAmount}
          inputMode="decimal"
          hint="Quanto há em dinheiro na gaveta. Ex.: 150,00."
        />

        <TextField
          id="caixa-fechamento-observacoes"
          label="Observações (opcional)"
          field={register('notes')}
          error={errors.notes}
          hint="Ex.: falta de troco."
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
            disabled={close.isPending}
            aria-busy={close.isPending}
            className={primaryButtonClassName}
          >
            Fechar caixa
          </button>
        </div>
      </form>
    </Modal>
  );
}
