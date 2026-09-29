import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { errorMessage } from '../../../shared/lib/problem';
import { TextField } from '../../../shared/ui/FormFields';
import { Modal } from '../../../shared/ui/Modal';
import { useCancelSale } from '../hooks/useSales';

/**
 * Cancelamento da venda aberta (1209b, `POST /sales/{id}/cancel`, passo 813): o motivo é obrigatório
 * porque o cancelamento é auditado, e a venda continua no histórico — desistir não apaga nada. Quem
 * confere `sale.cancel`, a posse da venda e o estado `OPEN` é o servidor; o botão só aparece para
 * quem tem a permissão e a venda aberta.
 *
 * O erro fica no banner do modal (o toast global só o repetiria): o 409 `SALE_ALREADY_COMPLETED`
 * chega com o texto do mapeador de `problem+json`, sem o id e o instante do `detail` do servidor.
 */

const cancelSchema = z.object({
  reason: z.string().trim().min(1, 'Informe o motivo do cancelamento.'),
});

type CancelFormValues = z.infer<typeof cancelSchema>;

export interface CancelSaleModalProps {
  saleId: string;
  onClose: () => void;
}

const secondaryButtonClassName =
  'min-h-10 rounded-md border border-line px-4 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none';

const dangerButtonClassName =
  'min-h-10 rounded-md bg-danger px-4 text-sm font-semibold text-white transition-colors duration-150 ease-out hover:bg-danger/90 disabled:cursor-not-allowed disabled:opacity-60 motion-reduce:transition-none';

export function CancelSaleModal({ saleId, onClose }: CancelSaleModalProps) {
  const cancel = useCancelSale();
  const [failure, setFailure] = useState<unknown>(null);
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<CancelFormValues>({
    resolver: zodResolver(cancelSchema),
    defaultValues: { reason: '' },
  });

  const submit = handleSubmit((values) => {
    setFailure(null);
    cancel.mutate(
      { saleId, body: { reason: values.reason.trim() } },
      { onSuccess: onClose, onError: setFailure },
    );
  });

  return (
    <Modal open title="Cancelar venda" onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        noValidate
        onSubmit={(event) => {
          void submit(event);
        }}
      >
        <p className="text-ink-muted">
          A venda continua no histórico com o motivo, o autor e o instante do cancelamento — nada é
          apagado.
        </p>

        <TextField
          id="cancelamento-motivo"
          label="Motivo"
          field={register('reason')}
          error={errors.reason}
          hint="Ex.: cliente desistiu da compra."
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
            Voltar
          </button>
          <button
            type="submit"
            disabled={cancel.isPending}
            aria-busy={cancel.isPending}
            className={dangerButtonClassName}
          >
            Confirmar cancelamento
          </button>
        </div>
      </form>
    </Modal>
  );
}
