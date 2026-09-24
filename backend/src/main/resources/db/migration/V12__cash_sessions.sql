-- Sessões de caixa (§5.3): abrir o caixa cria a sessão; fechar preenche a conferência e muda o
-- status. A sessão é o vínculo entre os movimentos de dinheiro e o caixa físico.
create table cash_sessions (
    id uuid primary key,
    store_id uuid not null references stores (id) on delete restrict,
    cash_register_id uuid not null references cash_registers (id) on delete restrict,
    status text not null check (status in ('OPEN', 'CLOSED')),
    opened_by_user_id uuid not null references users (id) on delete restrict,
    opened_at timestamptz not null,
    opening_amount numeric(14,2) not null check (opening_amount >= 0),
    -- Campos de fechamento (passo 611): nulos enquanto a sessão está aberta.
    closed_by_user_id uuid references users (id) on delete restrict,
    closed_at timestamptz,
    counted_amount numeric(14,2),
    expected_amount numeric(14,2),
    difference_amount numeric(14,2),
    closing_notes text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0
);

-- CONSTRAINT CHAVE (§5.3/§8): no máximo uma sessão aberta por caixa, garantida pelo banco — o
-- segundo operador a abrir o mesmo caixa recebe 409 CASH_REGISTER_ALREADY_OPEN, nunca um estado
-- inconsistente. O índice é parcial para o histórico de sessões fechadas conviver no caixa.
create unique index ux_cash_session_open on cash_sessions (cash_register_id) where status = 'OPEN';
