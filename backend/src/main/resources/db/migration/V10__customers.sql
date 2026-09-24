-- Clientes (§5.3): cadastro opcional na venda, com soft delete para preservar o histórico.
create table customers (
    id uuid primary key,
    store_id uuid not null references stores (id) on delete restrict,
    name text not null,
    tax_id text,
    phone text,
    email text,
    notes text,
    active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    version bigint not null default 0
);

-- CPF único por loja apenas entre clientes vivos: o soft delete libera o documento e o
-- índice parcial ignora os clientes sem CPF (nulos).
create unique index ux_customers_tax_id on customers (store_id, tax_id) where deleted_at is null and tax_id is not null;
