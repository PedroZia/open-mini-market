import { zodResolver } from '@hookform/resolvers/zod';
import { useForm, type UseFormSetError } from 'react-hook-form';
import { z } from 'zod';
import { formatMoney, parseDecimalInput } from '../../../shared/lib/money';
import { errorMessage, fieldErrors } from '../../../shared/lib/problem';
import { TextField } from '../../../shared/ui/FormFields';
import { Modal } from '../../../shared/ui/Modal';
import type { ProductResponse } from '../api/productsApi';
import { useChangeProductPrice } from '../hooks/useProducts';

/**
 * Alteração de preço com motivo (passo 411, `price.write`): o preço é a única forma de mudar o
 * valor — o `PUT` de cadastro não o aceita — e o motivo é obrigatório porque a alteração é
 * auditada. O valor digitado vai **cru** para o `PATCH /products/{id}/price` (BR-12): quem
 * arredonda é o servidor, com a escala da coluna. O recurso não exige `If-Match`.
 */

const priceSchema = z.object({
  price: z
    .string()
    .trim()
    .refine((value) => parseDecimalInput(value, 2) !== null, 'Informe um preço como 9,99.'),
  reason: z.string().trim().min(1, 'Informe o motivo da alteração.'),
});

type PriceFormValues = z.infer<typeof priceSchema>;

const secondaryButtonClassName =
  'min-h-10 rounded-md border border-line px-4 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none';

const primaryButtonClassName =
  'min-h-10 rounded-md bg-brand px-4 text-sm font-semibold text-white transition-colors duration-150 ease-out hover:bg-brand/90 disabled:cursor-not-allowed disabled:opacity-60 motion-reduce:transition-none';

export interface PriceModalProps {
  /** Produto escolhido na lista; o id é o da linha e o preço é o que a tela mostra hoje. */
  product: ProductResponse;
  onClose: () => void;
}

export function PriceModal({ product, onClose }: PriceModalProps) {
  const changePrice = useChangeProductPrice();
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<PriceFormValues>({
    resolver: zodResolver(priceSchema),
    defaultValues: { price: '', reason: '' },
  });

  const productId = product.id;
  if (productId === undefined) {
    return null;
  }

  const submit = handleSubmit((values) => {
    changePrice.mutate(
      {
        id: productId,
        body: {
          // O schema já barrou preço inválido; o número vai como o operador digitou.
          price: parseDecimalInput(values.price, 2) ?? 0,
          reason: values.reason.trim(),
        },
      },
      {
        onSuccess: onClose,
        onError: (failure) => {
          applyServerErrors(failure, setError);
        },
      },
    );
  });

  return (
    <Modal open title="Alterar preço" onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        noValidate
        onSubmit={(event) => {
          void submit(event);
        }}
      >
        <p className="text-ink-muted">
          {product.name ?? 'Produto'} está com{' '}
          <strong className="text-ink">{formatMoney(product.price ?? 0)}</strong>. A alteração fica
          registrada na auditoria com o motivo.
        </p>

        <TextField
          id="preco-novo"
          label="Novo preço (R$)"
          field={register('price')}
          error={errors.price}
          inputMode="decimal"
          hint="Ex.: 9,99."
        />

        <TextField
          id="preco-motivo"
          label="Motivo"
          field={register('reason')}
          error={errors.reason}
          hint="Ex.: reajuste do fornecedor."
        />

        {changePrice.error !== null && changePrice.error !== undefined ? (
          <p
            role="alert"
            className="rounded-md border border-danger/40 bg-danger/5 px-3 py-2 font-medium text-danger"
          >
            {errorMessage(changePrice.error)}
          </p>
        ) : null}

        <div className="flex justify-end gap-2">
          <button type="button" onClick={onClose} className={secondaryButtonClassName}>
            Cancelar
          </button>
          <button
            type="submit"
            disabled={changePrice.isPending}
            aria-busy={changePrice.isPending}
            className={primaryButtonClassName}
          >
            Salvar
          </button>
        </div>
      </form>
    </Modal>
  );
}

/** `errors[]` do `problem+json` no campo correspondente, quando o campo é deste formulário. */
function applyServerErrors(
  failure: unknown,
  setError: UseFormSetError<PriceFormValues>,
): void {
  for (const [field, message] of Object.entries(fieldErrors(failure))) {
    if (field === 'price' || field === 'reason') {
      setError(field, { type: 'server', message });
    }
  }
}
