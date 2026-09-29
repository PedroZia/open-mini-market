import type { ReactNode } from 'react';
import type { FieldError, UseFormRegisterReturn } from 'react-hook-form';

/**
 * Campos de formulário da retaguarda: `label` sempre ligado ao controle, erro do schema (ou do
 * `errors[]` do servidor) anunciado abaixo do campo e foco visível pelo global de `index.css`.
 * São só apresentação — quem valida é o Zod no formulário e o backend de novo (§10.3).
 */

const fieldClassName =
  'min-h-10 rounded-md border border-line bg-surface px-3 text-sm text-ink transition-colors duration-150 ease-out motion-reduce:transition-none read-only:bg-canvas read-only:text-ink-muted';

/** Erro do campo como o RHF o entrega (schema ou `errors[]` do servidor); nunca os dois. */
function messageOf(error: FieldError | undefined): string | undefined {
  return error?.message;
}

interface FieldFrameProps {
  id: string;
  label: string;
  error?: FieldError | undefined;
  hint?: string | undefined;
  children: ReactNode;
}

/** Rótulo + controle + dica/erro, no mesmo empilhamento em todos os campos. */
function FieldFrame({ id, label, error, hint, children }: FieldFrameProps) {
  const message = messageOf(error);
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-sm font-medium text-ink">
        {label}
      </label>
      {children}
      {hint !== undefined && message === undefined ? (
        <p className="text-xs text-ink-muted">{hint}</p>
      ) : null}
      {message !== undefined ? (
        <p id={`${id}-erro`} role="alert" className="text-sm text-danger">
          {message}
        </p>
      ) : null}
    </div>
  );
}

/** `aria-invalid`/`aria-describedby` do campo com erro, ligados ao parágrafo do `FieldFrame`. */
function ariaOf(id: string, error: FieldError | undefined) {
  return messageOf(error) === undefined
    ? {}
    : { 'aria-invalid': true, 'aria-describedby': `${id}-erro` };
}

export interface TextFieldProps {
  id: string;
  label: string;
  /** Registro do React Hook Form (`register('campo')`). */
  field: UseFormRegisterReturn;
  error?: FieldError | undefined;
  hint?: string | undefined;
  /** `decimal`/`numeric` abrem o teclado adequado no celular/tablet da retaguarda. */
  inputMode?: 'text' | 'decimal' | 'numeric';
  readOnly?: boolean;
  /** `password` esconde o valor digitado (senha do usuário e reset). */
  type?: 'text' | 'password';
}

export function TextField({
  id,
  label,
  field,
  error,
  hint,
  inputMode = 'text',
  readOnly = false,
  type = 'text',
}: TextFieldProps) {
  return (
    <FieldFrame id={id} label={label} error={error} hint={hint}>
      <input
        {...field}
        {...ariaOf(id, error)}
        id={id}
        type={type}
        inputMode={inputMode}
        readOnly={readOnly}
        className={fieldClassName}
      />
    </FieldFrame>
  );
}

export interface SelectFieldProps {
  id: string;
  label: string;
  field: UseFormRegisterReturn;
  error?: FieldError | undefined;
  hint?: string | undefined;
  children: ReactNode;
}

export function SelectField({ id, label, field, error, hint, children }: SelectFieldProps) {
  return (
    <FieldFrame id={id} label={label} error={error} hint={hint}>
      <select
        {...field}
        {...ariaOf(id, error)}
        id={id}
        className={fieldClassName}
      >
        {children}
      </select>
    </FieldFrame>
  );
}
