-- Loja única do MVP (§5.3). Nenhuma UI de multi-loja: a aplicação resolve a loja atual
-- por configuração (minimarket.store.default-code).
create table stores (
    id uuid primary key,
    code text not null unique,
    name text not null,
    timezone text not null default 'America/Sao_Paulo',
    allow_negative_stock boolean not null default true,
    max_discount_percent numeric(5,2) not null default 100,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0
);

-- Seed: loja 'MATRIZ'. Id gerado pela função nativa do PostgreSQL 18, já que este INSERT
-- é manutenção de schema e não passa pela geração de UUIDv7 da aplicação.
insert into stores (id, code, name) values (uuidv7(), 'MATRIZ', 'Matriz');
