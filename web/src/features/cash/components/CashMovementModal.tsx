import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm, type UseFormSetError } from 'react-hook-form';
import { z } from 'zod';
import { parseDecimalInput } from '../../../shared/lib/money';
import { errorMessage, fieldErrors } from '../../../shared/lib/problem';
import { TextField } from '../../../shared/ui/FormFields';
import { Modal } from '../../../shared/ui/Modal';
import { useRecordSupply, useRecordWithdrawal } from '../hooks/useCash';

/**
 * Sangria e suprimento (1210b) têm a mesma forma no contrato — `{amount, reason}` em
 * `POST /cash-registers/{id}/withdrawals` e `/{id}/supplies` — e o mesmo modal, mudando só o texto
 * da ação. O valor digitado em pt-BR vira o número cru do contrato (`12,50` → `12.5`), sem
 * arredondamento nenhum (BR-12); o motivo é obrigatório porque o movimento de dinheiro é auditado.
 *
 * Quem recusa valor não positivo, caixa sem sessão aberta ou falta de permissão é o servidor; o
 * formulário só evita frustrar o operador. Na sangria, o `aboveExpected` do servidor é aviso — o
 * movimento **foi registrado** — e nunca vira erro: o alerta é do hook, depois do sucesso.
 */

/** Valor em reais com a escala 2 do contrato; a conversão é de entrada, nunca de cálculo (BR-12). */
function parseAmount(input: string): number | null {
  return parseDecimalInput(input, 2);
}

const movementSchema = z.object({
  amount: z
    .string()
    .trim()
    .refine((value) => parseAmount(value) !== null, 'Informe o valor em reais. Ex.: 50,00.')
    .refine((value) => (parseAmount(value) ?? 0) > 0, 'O valor precisa ser maior que zero.'),
  reason: z.string().trim().min(1, 'Informe o motivo do movimento.'),
});

type MovementFormValues = z.infer<typeof movementSchema>;

/** Campos que aceitam `errors[]` do servidor; fora desta lista o erro fica no banner. */
const FORM_FIELDS: readonly (keyof MovementFormValues)[] = ['amount', 'reason'];

/** `true` quando o erro virou erro de campo; o banner fica só para o que não coube em campo. */
function applyServerErrors(
  failure: unknown,
  setError: UseFormSetError<MovementFormValues>,
): boolean {
  let mapped = false;
  for (const [field, message] of Object.entries(fieldErrors(failure))) {
    if ((FORM_FIELDS as readonly string[]).includes(field)) {
      setError(field as keyof MovementFormValues, { type: 'server', message });
      mapped = true;
    }
  }
  return mapped;
}

export type CashMovementKind = 'withdrawal' | 'supply';

/** Textos de cada ação: a forma é a mesma, o recado para o operador não. */
const KIND_TEXT = {
  withdrawal: {
    title: 'Sangria',
    intro:
      'Retirada de dinheiro da gaveta. O servidor recalcula o esperado — a tela não soma nem desconta nada.',
    hint: 'Ex.: troco levado ao cofre.',
    submit: 'Registrar sangria',
  },
  supply: {
    title: 'Suprimento',
    intro:
      'Entrada de dinheiro na gaveta. O servidor recalcula o esperado — a tela não soma nem desconta nada.',
    hint: 'Ex.: troco trazido do cofre.',
    submit: 'Registrar suprimento',
  },
} as const;

export interface CashMovementModalProps {
  cashRegisterId: string;
  kind: CashMovementKind;
  onClose: () => void;
}

const secondaryButtonClassName =
  'min-h-10 rounded-md border border-line px-4 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none';

const primaryButtonClassName =
  'min-h-10 rounded-md bg-brand px-4 text-sm font-semibold text-white transition-colors duration-150 ease-out hover:bg-brand/90 disabled:cursor-not-allowed disabled:opacity-60 motion-reduce:transition-none';

export function CashMovementModal({ cashRegisterId, kind, onClose }: CashMovementModalProps) {
  const withdrawal = useRecordWithdrawal();
  const supply = useRecordSupply();
  const movement = kind === 'withdrawal' ? withdrawal : supply;
  const text = KIND_TEXT[kind];
  const [unmapped, setUnmapped] = useState<unknown>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<MovementFormValues>({
    resolver: zodResolver(movementSchema),
    defaultValues: { amount: '', reason: '' },
  });

  const submit = handleSubmit((values) => {
    setUnmapped(null);
    movement.mutate(
      {
        cashRegisterId,
        body: {
          // O schema já barrou valor inválido ou não positivo; o número vai cru.
          amount: parseAmount(values.amount) ?? 0,
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
    <Modal open title={text.title} onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        noValidate
        onSubmit={(event) => {
          void submit(event);
        }}
      >
        <p className="text-ink-muted">{text.intro}</p>

        <TextField
          id={`caixa-${kind}-valor`}
          label="Valor (R$)"
          field={register('amount')}
          error={errors.amount}
          inputMode="decimal"
          hint="Ex.: 50,00."
        />

        <TextField
          id={`caixa-${kind}-motivo`}
          label="Motivo"
          field={register('reason')}
          error={errors.reason}
          hint={text.hint}
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
            disabled={movement.isPending}
            aria-busy={movement.isPending}
            className={primaryButtonClassName}
          >
            {text.submit}
          </button>
        </div>
      </form>
    </Modal>
  );
}
