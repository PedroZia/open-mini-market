import { zodResolver } from '@hookform/resolvers/zod';
import { ApiError } from '@minimarket/api-client';
import { useState } from 'react';
import { useForm, type UseFormSetError } from 'react-hook-form';
import { z } from 'zod';
import { errorMessage, fieldErrors } from '../../../shared/lib/problem';
import { TextField } from '../../../shared/ui/FormFields';
import { Modal } from '../../../shared/ui/Modal';
import type { CreateUserRequest, UpdateUserRequest, UserResponse } from '../api/usersApi';
import { useCreateUser, useRoles, useUpdateUser } from '../hooks/useUsers';

/**
 * Cadastro e edição de usuário (1208a) num formulário só: `user` ausente abre o cadastro e presente
 * abre a edição (username e senha não mudam por aqui — senha tem caso de uso próprio, o reset).
 * Diferente do produto, o recurso não tem `version` nem `If-Match`: o PUT é o último a salvar vence.
 *
 * A validação de tamanho da senha no cliente é conveniência (o servidor aplica a política de novo):
 * no cadastro o schema exige 8 caracteres; na edição o campo nem aparece.
 *
 * Erro do servidor: `errors[]` vira erro de campo; `USERNAME_ALREADY_EXISTS` (409, sem `errors[]`)
 * vai para o campo `username`; o resto (409 `CONFLICT` do último ADMIN ativo, 400 `UNKNOWN_ROLE`)
 * vira o banner — nunca o toast global, que só repetiria a mensagem.
 */

/** O cadastro exige a senha; na edição ela é semeada vazia e nunca viaja. */
function buildFormSchema(editing: boolean) {
  return z.object({
    username: z.string().trim().min(1, 'Informe o usuário.'),
    displayName: z.string().trim().min(1, 'Informe o nome.'),
    password: editing ? z.string() : z.string().min(8, 'A senha deve ter ao menos 8 caracteres.'),
    // Nenhum papel marcado é lista vazia (o servidor aceita); os códigos vêm do catálogo.
    roleCodes: z.array(z.string()),
  });
}

type UserFormValues = z.infer<ReturnType<typeof buildFormSchema>>;

/** Campos que aceitam `errors[]` do servidor; fora desta lista o erro não vira erro de campo. */
const FORM_FIELDS: readonly (keyof UserFormValues)[] = ['username', 'displayName', 'password'];

/** Checkbox sem nenhum marcado devolve `[]`; o corpo manda só strings (defesa contra o RHF). */
function selectedRoleCodes(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((code): code is string => typeof code === 'string') : [];
}

function toCreateBody(values: UserFormValues): CreateUserRequest {
  return {
    username: values.username.trim(),
    displayName: values.displayName.trim(),
    password: values.password,
    roleCodes: selectedRoleCodes(values.roleCodes),
  };
}

function toUpdateBody(values: UserFormValues): UpdateUserRequest {
  return {
    displayName: values.displayName.trim(),
    roleCodes: selectedRoleCodes(values.roleCodes),
  };
}

/** `true` quando o erro virou erro de campo; o banner fica só para o que não coube em campo. */
function applyServerErrors(
  failure: unknown,
  setError: UseFormSetError<UserFormValues>,
): boolean {
  let mapped = false;
  for (const [field, message] of Object.entries(fieldErrors(failure))) {
    if ((FORM_FIELDS as readonly string[]).includes(field)) {
      setError(field as keyof UserFormValues, { type: 'server', message });
      mapped = true;
    }
  }
  // Username em uso chega sem `errors[]`: o `code` estável diz onde pintar a mensagem.
  if (!mapped && failure instanceof ApiError && failure.code === 'USERNAME_ALREADY_EXISTS') {
    setError('username', { type: 'server', message: errorMessage(failure) });
    mapped = true;
  }
  return mapped;
}

export interface UserFormModalProps {
  /** Usuário em edição; ausente abre o cadastro. */
  user?: UserResponse | undefined;
  onClose: () => void;
}

const secondaryButtonClassName =
  'min-h-10 rounded-md border border-line px-4 text-sm font-medium text-ink transition-colors duration-150 ease-out hover:bg-canvas motion-reduce:transition-none';

const primaryButtonClassName =
  'min-h-10 rounded-md bg-brand px-4 text-sm font-semibold text-white transition-colors duration-150 ease-out hover:bg-brand/90 disabled:cursor-not-allowed disabled:opacity-60 motion-reduce:transition-none';

export function UserFormModal({ user, onClose }: UserFormModalProps) {
  const userId = user?.id;
  const editing = userId !== undefined;
  const create = useCreateUser();
  const update = useUpdateUser();
  const roles = useRoles();
  const [unmapped, setUnmapped] = useState<unknown>(null);

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<UserFormValues>({
    resolver: zodResolver(buildFormSchema(editing)),
    // O modal é montado a cada abertura, então os defaults já são o usuário clicado.
    defaultValues: {
      username: user?.username ?? '',
      displayName: user?.displayName ?? '',
      password: '',
      roleCodes: [...(user?.roles ?? [])],
    },
  });

  const pending = editing ? update.isPending : create.isPending;

  const submit = handleSubmit((values) => {
    setUnmapped(null);
    const onError = (cause: unknown) => {
      if (!applyServerErrors(cause, setError)) {
        setUnmapped(cause);
      }
    };

    if (userId !== undefined) {
      update.mutate({ id: userId, body: toUpdateBody(values) }, { onSuccess: onClose, onError });
      return;
    }
    create.mutate(toCreateBody(values), { onSuccess: onClose, onError });
  });

  const title = editing ? 'Editar usuário' : 'Novo usuário';

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
          id="usuario-username"
          label="Usuário"
          field={register('username')}
          error={errors.username}
          readOnly={editing}
          hint={editing ? 'Não muda depois do cadastro.' : 'O login, sem espaços; o servidor normaliza.'}
        />

        <TextField
          id="usuario-nome"
          label="Nome"
          field={register('displayName')}
          error={errors.displayName}
          hint="Como o operador aparece no sistema."
        />

        {editing ? null : (
          <TextField
            id="usuario-senha"
            label="Senha"
            field={register('password')}
            error={errors.password}
            type="password"
            hint="Mínimo de 8 caracteres; o usuário poderá trocá-la no primeiro acesso."
          />
        )}

        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-medium text-ink">Papéis</legend>
          {roles.isPending ? <p className="text-sm text-ink-muted">Carregando papéis…</p> : null}
          {roles.error !== null && roles.error !== undefined ? (
            <p role="alert" className="text-sm text-danger">
              {errorMessage(roles.error)}
            </p>
          ) : null}
          {roles.data?.map((role) => {
            const code = role.code;
            return code === undefined ? null : (
              <label key={code} className="flex items-center gap-2 text-sm text-ink">
                <input type="checkbox" value={code} {...register('roleCodes')} className="size-4" />
                {role.name ?? code}
              </label>
            );
          })}
          <p className="text-xs text-ink-muted">Sem papel nenhum o usuário entra, mas não pode nada.</p>
        </fieldset>

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
