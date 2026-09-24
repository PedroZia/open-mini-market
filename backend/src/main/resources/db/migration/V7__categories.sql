-- Categorias do catálogo (§5.3): agrupam produtos e podem formar hierarquia via parent_id.
-- O nome é único por loja — duas lojas podem ter a mesma categoria.
create table categories (
    id uuid primary key,
    store_id uuid not null references stores (id) on delete restrict,
    name text not null,
    parent_id uuid references categories (id) on delete restrict,
    active boolean not null default true,
    sort_order int not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    constraint ux_categories_store_name unique (store_id, name)
);
