-- Venda (§5.3): nasce OPEN no caixa da sessão de caixa (passo 805) e só conclui quando o
-- pagamento cobre o total (BR-05). number é sequencial por loja, alocado em document_sequences
-- (passo 804); totais e desconto são sempre recalculados no servidor (BR-02/BR-03/BR-12), e o
-- desconto guarda tipo/valor/motivo para a auditoria do passo 810.
create table sales (
    id uuid primary key,
    store_id uuid not null references stores (id) on delete restrict,
    number bigint not null,
    cash_session_id uuid not null references cash_sessions (id) on delete restrict,
    cash_register_id uuid not null references cash_registers (id) on delete restrict,
    customer_id uuid references customers (id) on delete restrict,
    operator_user_id uuid not null references users (id) on delete restrict,
    status text not null check (status in ('OPEN', 'COMPLETED', 'CANCELLED')),
    subtotal numeric(14,2) not null default 0,
    discount_type text check (discount_type in ('VALUE', 'PERCENT')),
    discount_value numeric(14,2),
    discount_amount numeric(14,2) not null default 0,
    discount_reason text,
    discount_authorized_by_user_id uuid references users (id) on delete restrict,
    total numeric(14,2) not null default 0,
    paid_amount numeric(14,2) not null default 0,
    change_amount numeric(14,2) not null default 0,
    item_count int not null default 0,
    notes text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    completed_at timestamptz,
    cancelled_at timestamptz,
    cancelled_by_user_id uuid references users (id) on delete restrict,
    cancel_reason text,
    version bigint not null default 0,
    -- Número da venda é único dentro da loja (§5.3): duas vendas não podem ter o mesmo número.
    constraint ux_sales_store_number unique (store_id, number)
);

-- Vendas da sessão de caixa (passo 814: fechar o caixa exige nenhuma venda aberta; relatórios
-- de conferência).
create index ix_sales_cash_session on sales (cash_session_id);
-- Histórico da loja em ordem cronológica (§5.3): alimenta a consulta de vendas (passo 812).
create index ix_sales_store_created_at on sales (store_id, created_at desc);
-- Vendas de um operador em ordem cronológica (§5.3): auditoria e desempenho por operador.
create index ix_sales_operator_created_at on sales (operator_user_id, created_at desc);
-- Vendas abertas por loja (§5.3): índice parcial porque só as OPEN interessam às consultas
-- operacionais — o histórico concluído/cancelado não entra nele.
create index ix_sales_store_open on sales (store_id) where status = 'OPEN';
