-- Movimentos de dinheiro da sessão de caixa (§5.3): o ledger do que entra e sai do caixa.
-- amount é assinado — suprimento/venda/abertura positivos, sangria negativa.
create table cash_movements (
    id uuid primary key,
    store_id uuid not null references stores (id) on delete restrict,
    cash_session_id uuid not null references cash_sessions (id) on delete restrict,
    type text not null check (type in ('OPENING', 'SALE', 'WITHDRAWAL', 'SUPPLY')),
    amount numeric(14,2) not null,
    payment_method text,
    reference_type text,
    reference_id uuid,
    reason text,
    created_by_user_id uuid not null references users (id) on delete restrict,
    created_at timestamptz not null default now()
);

-- Leitura dos movimentos da sessão em ordem cronológica (§5.3): alimenta o saldo esperado e o
-- resumo do caixa (passos 608 e 611).
create index ix_cash_movements_session_created_at on cash_movements (cash_session_id, created_at);
