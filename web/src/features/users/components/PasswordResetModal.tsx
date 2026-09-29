import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm, type UseFormSetError } from 'react-hook-form';
import { z } from 'zod';
import { errorMessage, fieldErrors } from '../../../shared/lib/problem';
import { TextField } from '../../../shared/ui/FormFields';
import { Modal } from '../../../shared/ui/Modal';
import { useResetPassword } from '../hooks/useUsers';

/**
 * Reset de senha pelo ADMIN (1208a, `POST /users/{id}/password-reset`, passo 113): define a senha
 * temporária, marca `mustChangePassword` e derruba as sessões do usuário (passo 213) — o aviso na
 * tela existe para o operador saber que o acesso atual cai junto.
 *
 * O mínimo de 8 caracteres é a política mínima do servidor (`CreateUserUseCase`); o Zod repete como
 * conveniência. Erro do servidor (senha curta, 404): banner no modal, sem toast duplicado.
 */

const resetSchema = z.object({
  newPassword: z.string().min(8, 'A senha deve ter ao menos 8 caracteres.'),
});

type ResetFormValues = z.infer<typeof resetSchema>;

/** Campos que aceitam `errors[]` do servidor; fora desta lista o erro não vira erro de campo. */
const FORM_FIELDS: readonly (keyof ResetFormValues)[] = ['newPassword'];

/** `true` quando o erro virou erro de campo; o banner fica só para o que não coube em campo. */
function applyServerErrors(
  failure: unknown,
  setError: UseFormSetError<ResetFormValues>,
): boolean {
  let mapped = false;
  for (const [field, message] of Object.entries(fieldErrors(failure))) {
    if ((FORM_FIELDS as readonly string[]).includes(field)) {
      setError(field as keyof ResetFormValues, { type: 'server', message });
      mapped = true;
    }
  }
  return mapped;
}

export interface PasswordResetModalProps {
  /** Usuário alvo do reset; o id é o que viaja. */
  userId: string;
  /** Username só para a frase do aviso. */
  username: string;
  onClose: () => void;
}

const secondaryButtonClassName =
  'min-h-10 rounded-md border border-line px-4 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none';

const primaryButtonClassName =
  'min-h-10 rounded-md bg-brand px-4 text-sm font-semibold text-white transition-colors duration-150 ease-out hover:bg-brand/90 disabled:cursor-not-allowed disabled:opacity-60 motion-reduce:transition-none';

export function PasswordResetModal({ userId, username, onClose }: PasswordResetModalProps) {
  const reset = useResetPassword();
  const [unmapped, setUnmapped] = useState<unknown>(null);

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<ResetFormValues>({
    resolver: zodResolver(resetSchema),
    defaultValues: { newPassword: '' },
  });

  const submit = handleSubmit((values) => {
    setUnmapped(null);
    reset.mutate(
      { id: userId, body: { newPassword: values.newPassword } },
      {
        onSuccess: onClose,
        onError: (cause: unknown) => {
          if (!applyServerErrors(cause, setError)) {
            setUnmapped(cause);
          }
        },
      },
    );
  });

  return (
    <Modal open title={`Redefinir senha de ${username}`} onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        noValidate
        onSubmit={(event) => {
          void submit(event);
        }}
      >
        <p className="rounded-md border border-line bg-canvas px-3 py-2 text-ink-muted">
          A senha atual deixa de valer e as sessões abertas deste usuário serão encerradas; no
          próximo acesso ele terá de trocar a senha.
        </p>

        <TextField
          id="usuario-nova-senha"
          label="Nova senha"
          field={register('newPassword')}
          error={errors.newPassword}
          type="password"
          hint="Mínimo de 8 caracteres."
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
            disabled={reset.isPending}
            aria-busy={reset.isPending}
            className={primaryButtonClassName}
          >
            Redefinir
          </button>
        </div>
      </form>
    </Modal>
  );
}
