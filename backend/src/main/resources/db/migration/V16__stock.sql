-- Saldo de estoque por produto/loja (§5.3): uma linha por par (store_id, product_id), criada sob
-- demanda pelo StockService (passo 703) e alterada sempre sob lock pessimista (§8). quantity é o
-- cache do último balance_after do ledger — a evolução do saldo é reconstruível pelos movimentos.
-- Sem created_at de propósito: o §5.3 define só updated_at (a linha nasce no primeiro movimento).
create table product_stocks (
    id uuid primary key,
    store_id uuid not null references stores (id) on delete restrict,
    product_id uuid not null references products (id) on delete restrict,
    quantity numeric(14,3) not null default 0,
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    constraint ux_product_stocks_store_product unique (store_id, product_id)
);

-- Ledger de estoque append-only (§5.3/§7.3): toda alteração de saldo vira uma linha com o delta
-- assinado (quantity_delta) e o saldo resultante (balance_after), o que torna a evolução do saldo
-- auditável sem recalcular o histórico. unit_cost guarda o custo da entrada, quando informado.
create table stock_movements (
    id uuid primary key,
    store_id uuid not null references stores (id) on delete restrict,
    product_id uuid not null references products (id) on delete restrict,
    type text not null check (type in ('INITIAL', 'PURCHASE_IN', 'SALE_OUT', 'RETURN_IN', 'ADJUSTMENT', 'LOSS')),
    quantity_delta numeric(14,3) not null,
    balance_after numeric(14,3) not null,
    unit_cost numeric(14,2),
    reference_type text,
    reference_id uuid,
    reason text,
    created_by_user_id uuid not null references users (id) on delete restrict,
    created_at timestamptz not null default now()
);

-- Histórico de um produto em ordem cronológica (§5.3): alimenta o detalhe de estoque (passo 704).
create index ix_stock_movements_product_created_at on stock_movements (product_id, created_at desc);
-- Rastro do documento que originou o movimento (venda, ajuste, recebimento).
create index ix_stock_movements_reference on stock_movements (reference_type, reference_id);
-- Movimentos da loja por período (§5.3): relatórios de entrada/saída/perda.
create index ix_stock_movements_store_created_at on stock_movements (store_id, created_at desc);

-- Append-only no nível do banco (§5.3/§7.3): a role da aplicação recebe só SELECT/INSERT no
-- ledger — sem UPDATE/DELETE. A role é criada de forma idempotente na V6 para o deploy apontar o
-- usuário da aplicação para ela; em dev/test a aplicação conecta como o dono das tabelas, então
-- nenhum grant restringe essas conexões e o teste de integração assume a role com SET LOCAL ROLE
-- para provar a garantia.
grant select, insert on table stock_movements to minimarket_app;
