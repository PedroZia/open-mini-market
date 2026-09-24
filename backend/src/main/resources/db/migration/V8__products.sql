-- Produtos do catálogo (§5.3): preço em numeric(14,2), quantidade em numeric(14,3).
-- O soft delete (deleted_at) preserva o histórico; o barcode só é único entre produtos vivos.
create table products (
    id uuid primary key,
    store_id uuid not null references stores (id) on delete restrict,
    barcode text,
    sku text,
    name text not null,
    description text,
    category_id uuid references categories (id) on delete restrict,
    unit text not null check (unit in ('UN', 'KG')),
    price numeric(14,2) not null check (price >= 0),
    cost_price numeric(14,2) check (cost_price >= 0),
    min_quantity numeric(14,3),
    active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    version bigint not null default 0
);

-- Barcode único por loja apenas entre produtos vivos: o soft delete libera o código e o
-- índice parcial ignora os produtos sem barcode (nulos).
create unique index ux_products_barcode on products (store_id, barcode) where deleted_at is null and barcode is not null;

-- Listagem por loja/ativo e filtro por categoria (§9).
create index ix_products_store_active on products (store_id, active);
create index ix_products_category_id on products (category_id);
